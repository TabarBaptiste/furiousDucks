#!/usr/bin/env bash
###############################################################################
# backup-all.sh - Furious Ducks CI/CD Backup Script
#
# Backs up all Docker volumes used by the CI/CD infrastructure to a Restic
# repository. Initializes the repository if needed, tags each backup, and
# sends a notification on failure.
#
# Required environment variables:
#   RESTIC_REPOSITORY  - Restic repository location (e.g. s3:... or /backup)
#   RESTIC_PASSWORD    - Restic repository password
#
# Optional environment variables:
#   WEBHOOK_URL        - URL to send failure notifications (default: placeholder)
#   LOG_DIR            - Directory for log files (default: /var/log/backup)
###############################################################################
set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
WEBHOOK_URL="${WEBHOOK_URL:-https://chat.wk-archi-o24a-4-5-g1.fr/hooks/CHANGE_ME}"
LOG_DIR="${LOG_DIR:-/var/log/backup}"
LOG_FILE="${LOG_DIR}/backup-$(date +%Y%m%d-%H%M%S).log"
# Volumes are mounted read-only under /backup-sources/ in the container
# (see docker-compose.backup.yml)
VOLUMES_BASE="${VOLUMES_BASE:-/backup-sources}"

# Volumes to back up: directory_name -> tag
declare -A BACKUP_TARGETS=(
  ["gitlab-config"]="gitlab-config"
  ["gitlab-data"]="gitlab-data"
  ["jenkins-data"]="jenkins"
  ["prometheus-data"]="prometheus"
  ["grafana-data"]="grafana"
  ["sonarqube-data"]="sonarqube"
)

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
  log "ERROR" "Sending failure notification..."
  curl -sf -X POST -H 'Content-type: application/json' \
    --data "{\"text\":\"[BACKUP FAILURE] ${message}\"}" \
    "${WEBHOOK_URL}" >> "${LOG_FILE}" 2>&1 || \
    log "WARN" "Failed to send notification to webhook"
}

cleanup() {
  local exit_code=$?
  if [[ ${exit_code} -ne 0 ]]; then
    log "ERROR" "Backup failed with exit code ${exit_code}"
    notify_failure "Backup script exited with code ${exit_code}. Check ${LOG_FILE} for details."
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
# Initialize repository if needed
# ---------------------------------------------------------------------------
log "INFO" "Checking Restic repository status..."
if ! restic snapshots > /dev/null 2>&1; then
  log "INFO" "Repository not initialized. Initializing..."
  if restic init >> "${LOG_FILE}" 2>&1; then
    log "INFO" "Repository initialized successfully"
  else
    log "ERROR" "Failed to initialize Restic repository"
    exit 1
  fi
else
  log "INFO" "Repository already initialized"
fi

# ---------------------------------------------------------------------------
# Perform backups
# ---------------------------------------------------------------------------
BACKUP_START="$(date +%s)"
log "INFO" "========== Backup started at $(date '+%Y-%m-%d %H:%M:%S') =========="

FAILED=0
SUCCEEDED=0

for volume in "${!BACKUP_TARGETS[@]}"; do
  tag="${BACKUP_TARGETS[${volume}]}"
  volume_path="${VOLUMES_BASE}/${volume}"

  log "INFO" "Backing up volume '${volume}' (tag: ${tag})..."

  if [[ ! -d "${volume_path}" ]]; then
    log "WARN" "Volume path '${volume_path}' does not exist, skipping"
    continue
  fi

  vol_start="$(date +%s)"

  if restic backup "${volume_path}" \
    --tag "${tag}" \
    --tag "daily" \
    --verbose >> "${LOG_FILE}" 2>&1; then

    vol_end="$(date +%s)"
    vol_duration=$(( vol_end - vol_start ))
    log "INFO" "  -> Volume '${volume}' backed up successfully (${vol_duration}s)"
    (( SUCCEEDED++ ))
  else
    log "ERROR" "  -> Failed to back up volume '${volume}'"
    (( FAILED++ ))
  fi
done

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
BACKUP_END="$(date +%s)"
TOTAL_DURATION=$(( BACKUP_END - BACKUP_START ))

log "INFO" "========== Backup finished at $(date '+%Y-%m-%d %H:%M:%S') =========="
log "INFO" "Duration: ${TOTAL_DURATION}s | Succeeded: ${SUCCEEDED} | Failed: ${FAILED}"

if [[ ${FAILED} -gt 0 ]]; then
  notify_failure "${FAILED} volume(s) failed to back up. Succeeded: ${SUCCEEDED}. Duration: ${TOTAL_DURATION}s."
  exit 1
fi

log "INFO" "All backups completed successfully"
exit 0
