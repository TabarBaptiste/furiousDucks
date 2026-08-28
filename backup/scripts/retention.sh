#!/usr/bin/env bash
###############################################################################
# retention.sh - Furious Ducks Backup Retention Policy
#
# Politique Grandfather-Father-Son (section 3.4 du cahier des specs) :
#   - Keep last 7 daily snapshots
#   - Keep last 4 weekly snapshots
#   - Keep last 6 monthly snapshots
#   - Keep last 2 yearly snapshots
#
# Prunes unreferenced data and verifies repository integrity after cleanup.
#
# Required environment variables:
#   RESTIC_REPOSITORY  - Restic repository location
#   RESTIC_PASSWORD    - Restic repository password
#
# Optional environment variables:
#   WEBHOOK_URL        - URL for failure notifications
#   LOG_DIR            - Directory for log files (default: /var/log/backup)
#   DRY_RUN            - Set to "true" for dry-run mode (default: false)
###############################################################################
set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
WEBHOOK_URL="${WEBHOOK_URL:-https://chat.wk-archi-o24a-4-5-g1.fr/hooks/CHANGE_ME}"
LOG_DIR="${LOG_DIR:-/var/log/backup}"
LOG_FILE="${LOG_DIR}/retention-$(date +%Y%m%d-%H%M%S).log"
DRY_RUN="${DRY_RUN:-false}"

# Retention policy - GFS (Grandfather-Father-Son)
KEEP_DAILY=7
KEEP_WEEKLY=4
KEEP_MONTHLY=6
KEEP_YEARLY=2

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

notify_failure() {
  local message="$1"
  log "ERROR" "Sending failure alert..."
  curl -sf -X POST -H 'Content-type: application/json' \
    --data "{\"text\":\"[RETENTION ALERT] ${message}\"}" \
    "${WEBHOOK_URL}" >> "${LOG_FILE}" 2>&1 || \
    log "WARN" "Failed to send notification to webhook"
}

cleanup() {
  local exit_code=$?
  if [[ ${exit_code} -ne 0 ]]; then
    log "ERROR" "Retention script failed with exit code ${exit_code}"
    notify_failure "Retention cleanup exited with code ${exit_code}. Check ${LOG_FILE}."
  fi
}
trap cleanup EXIT

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
# Count snapshots before cleanup
# ---------------------------------------------------------------------------
log "INFO" "========== Retention Cleanup Started =========="
log "INFO" "Policy: keep-daily=${KEEP_DAILY}, keep-weekly=${KEEP_WEEKLY}, keep-monthly=${KEEP_MONTHLY}, keep-yearly=${KEEP_YEARLY}"

SNAPSHOTS_BEFORE="$(restic snapshots --json 2>/dev/null | grep -o '"short_id"' | wc -l)"
log "INFO" "Snapshots before cleanup: ${SNAPSHOTS_BEFORE}"

# ---------------------------------------------------------------------------
# Apply retention policy
# ---------------------------------------------------------------------------
FORGET_FLAGS=(
  # Group by paths only: the container hostname is the Docker container ID and
  # changes on every recreation. The default host+paths grouping would give
  # each incarnation its own 7/4/6/2 quota, so old groups would never expire.
  --group-by paths
  --keep-daily "${KEEP_DAILY}"
  --keep-weekly "${KEEP_WEEKLY}"
  --keep-monthly "${KEEP_MONTHLY}"
  --keep-yearly "${KEEP_YEARLY}"
  --prune
  --verbose
)

if [[ "${DRY_RUN}" == "true" ]]; then
  log "INFO" "DRY RUN mode enabled - no snapshots will be removed"
  FORGET_FLAGS+=( --dry-run )
fi

log "INFO" "Running restic forget with pruning..."
RETENTION_START="$(date +%s)"

if restic forget "${FORGET_FLAGS[@]}" >> "${LOG_FILE}" 2>&1; then
  RETENTION_END="$(date +%s)"
  RETENTION_DURATION=$(( RETENTION_END - RETENTION_START ))
  log "INFO" "Retention policy applied successfully (${RETENTION_DURATION}s)"
else
  log "ERROR" "restic forget failed"
  exit 1
fi

# ---------------------------------------------------------------------------
# Count snapshots after cleanup
# ---------------------------------------------------------------------------
SNAPSHOTS_AFTER="$(restic snapshots --json 2>/dev/null | grep -o '"short_id"' | wc -l)"
CLEANED=$(( SNAPSHOTS_BEFORE - SNAPSHOTS_AFTER ))

if [[ ${CLEANED} -lt 0 ]]; then
  CLEANED=0
fi

log "INFO" "Snapshots after cleanup: ${SNAPSHOTS_AFTER}"
log "INFO" "Snapshots removed: ${CLEANED}"

# ---------------------------------------------------------------------------
# Post-prune integrity check
# ---------------------------------------------------------------------------
log "INFO" "Running post-prune integrity check..."

if restic check >> "${LOG_FILE}" 2>&1; then
  log "INFO" "Post-prune integrity check PASSED"
else
  log "ERROR" "Post-prune integrity check FAILED"
  notify_failure "Repository integrity check failed after pruning. Removed ${CLEANED} snapshot(s)."
  exit 1
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
log "INFO" "========== Retention Cleanup Complete =========="
log "INFO" "Removed ${CLEANED} snapshot(s). Repository is healthy."
exit 0
