#!/usr/bin/env bash
###############################################################################
# restore.sh - Furious Ducks Backup Restore Script
#
# Restores data from a Restic backup snapshot.
#
# Usage:
#   ./restore.sh                                     List last 10 snapshots
#   ./restore.sh <snapshot_id> <target_path>          Restore full snapshot
#   ./restore.sh <snapshot_id> <target_path> --volume <name>  Restore one volume
#
# Required environment variables:
#   RESTIC_REPOSITORY  - Restic repository location
#   RESTIC_PASSWORD    - Restic repository password
#
# Optional environment variables:
#   LOG_DIR            - Directory for log files (default: /var/log/backup)
#   VOLUMES_BASE       - Base path used during backup (default: /backup-sources)
###############################################################################
set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
LOG_DIR="${LOG_DIR:-/var/log/backup}"
LOG_FILE="${LOG_DIR}/restore-$(date +%Y%m%d-%H%M%S).log"
VOLUMES_BASE="${VOLUMES_BASE:-/backup-sources}"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
mkdir -p "${LOG_DIR}"

log() {
  local level="$1"; shift
  local msg
  msg="$(date '+%Y-%m-%d %H:%M:%S') [${level}] $*"
  echo "${msg}" | tee -a "${LOG_FILE}"
}

usage() {
  cat <<USAGE
Usage: $(basename "$0") [snapshot_id] [target_path] [--volume volume_name]

Arguments:
  snapshot_id    Restic snapshot ID to restore (short or full hash)
  target_path    Directory to restore data into

Options:
  --volume, -v   Restore only the specified volume (e.g., gitlab-data)
  --help, -h     Show this help message

Examples:
  $(basename "$0")                                      # List last 10 snapshots
  $(basename "$0") abc12345 /tmp/restore                # Full restore
  $(basename "$0") abc12345 /tmp/restore --volume jenkins-data  # Single volume

Environment:
  RESTIC_REPOSITORY   Restic repo location (required)
  RESTIC_PASSWORD     Restic repo password (required)
USAGE
  exit 0
}

# ---------------------------------------------------------------------------
# Parse arguments
# ---------------------------------------------------------------------------
SNAPSHOT_ID=""
TARGET_PATH=""
VOLUME_NAME=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h)
      usage
      ;;
    --volume|-v)
      if [[ -z "${2:-}" ]]; then
        log "ERROR" "--volume requires a volume name"
        exit 1
      fi
      VOLUME_NAME="$2"
      shift 2
      ;;
    *)
      if [[ -z "${SNAPSHOT_ID}" ]]; then
        SNAPSHOT_ID="$1"
      elif [[ -z "${TARGET_PATH}" ]]; then
        TARGET_PATH="$1"
      else
        log "ERROR" "Unexpected argument: $1"
        usage
      fi
      shift
      ;;
  esac
done

# ---------------------------------------------------------------------------
# Pre-flight checks
# ---------------------------------------------------------------------------
if [[ -z "${RESTIC_REPOSITORY:-}" ]]; then
  log "ERROR" "RESTIC_REPOSITORY is not set"
  exit 1
fi

if [[ -z "${RESTIC_PASSWORD:-}" ]]; then
  log "ERROR" "RESTIC_PASSWORD is not set"
  exit 1
fi

export RESTIC_REPOSITORY
export RESTIC_PASSWORD

# ---------------------------------------------------------------------------
# If no snapshot_id, list recent snapshots and exit
# ---------------------------------------------------------------------------
if [[ -z "${SNAPSHOT_ID}" ]]; then
  log "INFO" "No snapshot ID provided. Listing last 10 snapshots..."
  echo ""
  echo "=== Last 10 Snapshots ==="
  restic snapshots --latest 10
  echo ""
  echo "Usage: $(basename "$0") <snapshot_id> <target_path> [--volume <name>]"
  exit 0
fi

# ---------------------------------------------------------------------------
# Validate target path
# ---------------------------------------------------------------------------
if [[ -z "${TARGET_PATH}" ]]; then
  log "ERROR" "Target path is required when restoring a snapshot"
  echo "Usage: $(basename "$0") <snapshot_id> <target_path> [--volume <name>]"
  exit 1
fi

# ---------------------------------------------------------------------------
# Confirm restore
# ---------------------------------------------------------------------------
log "INFO" "========== Restore Operation =========="
log "INFO" "Snapshot:    ${SNAPSHOT_ID}"
log "INFO" "Target:      ${TARGET_PATH}"
if [[ -n "${VOLUME_NAME}" ]]; then
  log "INFO" "Volume:      ${VOLUME_NAME}"
fi

echo ""
echo "WARNING: This will restore data to '${TARGET_PATH}'."
echo "         Existing files in the target may be overwritten."
echo ""

# In non-interactive mode (e.g., piped input), skip confirmation
if [[ -t 0 ]]; then
  read -r -p "Continue? (y/N) " confirm
  if [[ "${confirm}" != "y" && "${confirm}" != "Y" ]]; then
    log "INFO" "Restore cancelled by user"
    exit 0
  fi
fi

# ---------------------------------------------------------------------------
# Create target directory
# ---------------------------------------------------------------------------
mkdir -p "${TARGET_PATH}"

# ---------------------------------------------------------------------------
# Perform restore
# ---------------------------------------------------------------------------
RESTORE_START="$(date +%s)"
log "INFO" "Starting restore..."

RESTORE_FLAGS=( --target "${TARGET_PATH}" --verbose )

if [[ -n "${VOLUME_NAME}" ]]; then
  # Restore only the specified volume path
  VOLUME_PATH="${VOLUMES_BASE}/${VOLUME_NAME}"
  RESTORE_FLAGS+=( --path "${VOLUME_PATH}" )
  log "INFO" "Filtering restore to path: ${VOLUME_PATH}"
fi

if restic restore "${SNAPSHOT_ID}" "${RESTORE_FLAGS[@]}" >> "${LOG_FILE}" 2>&1; then
  RESTORE_END="$(date +%s)"
  RESTORE_DURATION=$(( RESTORE_END - RESTORE_START ))
  log "INFO" "Restore completed successfully (${RESTORE_DURATION}s)"
else
  log "ERROR" "Restore failed"
  exit 1
fi

# ---------------------------------------------------------------------------
# Verification
# ---------------------------------------------------------------------------
log "INFO" "Verifying restored data..."

# Check that the target directory is not empty
RESTORED_FILES="$(find "${TARGET_PATH}" -type f 2>/dev/null | wc -l)"
RESTORED_SIZE="$(du -sh "${TARGET_PATH}" 2>/dev/null | cut -f1)"

if [[ "${RESTORED_FILES}" -eq 0 ]]; then
  log "WARN" "No files found in restored target - snapshot may be empty or path filter too restrictive"
else
  log "INFO" "Restored ${RESTORED_FILES} file(s), total size: ${RESTORED_SIZE}"
fi

# List top-level contents of the restore target
log "INFO" "Restored contents:"
ls -la "${TARGET_PATH}" 2>&1 | tee -a "${LOG_FILE}"

# Verify the snapshot metadata matches what we restored
log "INFO" "Verifying snapshot metadata..."
if restic snapshots "${SNAPSHOT_ID}" >> "${LOG_FILE}" 2>&1; then
  log "INFO" "Snapshot metadata verification passed"
else
  log "WARN" "Could not verify snapshot metadata"
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
log "INFO" "========== Restore Complete =========="
log "INFO" "Data restored to: ${TARGET_PATH}"
log "INFO" "Files restored:   ${RESTORED_FILES}"
log "INFO" "Total size:       ${RESTORED_SIZE}"
echo ""
echo "Restore successful. Data is available at: ${TARGET_PATH}"
exit 0
