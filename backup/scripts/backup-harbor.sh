#!/usr/bin/env bash
###############################################################################
# backup-harbor.sh - Furious Ducks Harbor Registry Backup
#
# Backs up the Harbor registry (images, Harbor's own PostgreSQL, Redis, job
# service data) to Restic. Harbor is deployed separately from the main
# docker-compose stacks via its official installer (see docs/04-harbor.md)
# and stores everything under a single host bind mount (data_volume in
# harbor.yml, default /data) rather than named Docker volumes - hence a
# dedicated script instead of a BACKUP_TARGETS entry in backup-all.sh.
#
# Runs weekly (not daily like backup-all.sh): critical/promoted images only
# change at release cadence (release/* and hotfix/* branches, GitFlow), and
# Harbor's own retention policy (10 tagged versions/project, untagged purged
# after 7 days) already keeps the on-disk content lean. See CST section
# "7.5 Pourquoi un backup hebdomadaire pour Harbor plutôt que quotidien ?".
#
# Required environment variables:
#   RESTIC_REPOSITORY  - Restic repository location
#   RESTIC_PASSWORD    - Restic repository password
#
# Optional environment variables:
#   WEBHOOK_URL         - URL to send failure notifications
#   LOG_DIR             - Directory for log files (default: /var/log/backup)
#   HARBOR_DATA_DIR     - Mounted Harbor bind mount (default: /backup-sources/harbor-registry)
###############################################################################
set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
WEBHOOK_URL="${WEBHOOK_URL:-https://chat.wk-archi-o24a-4-5-g1.fr/hooks/CHANGE_ME}"
LOG_DIR="${LOG_DIR:-/var/log/backup}"
LOG_FILE="${LOG_DIR}/backup-harbor-$(date +%Y%m%d-%H%M%S).log"
HARBOR_DATA_DIR="${HARBOR_DATA_DIR:-/backup-sources/harbor-registry}"

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
    --data "{\"text\":\"[ÉCHEC SAUVEGARDE] ${message}\"}" \
    "${WEBHOOK_URL}" >> "${LOG_FILE}" 2>&1 || \
    log "WARN" "Failed to send notification to webhook"
}

cleanup() {
  local exit_code=$?
  if [[ ${exit_code} -ne 0 ]]; then
    log "ERROR" "Harbor backup failed with exit code ${exit_code}"
    notify_failure "La sauvegarde du registre Harbor s'est terminée avec le code ${exit_code}. Consultez ${LOG_FILE} pour plus de détails."
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

if [[ ! -d "${HARBOR_DATA_DIR}" ]]; then
  log "WARN" "Harbor data directory '${HARBOR_DATA_DIR}' not found - is Harbor deployed on this host? Skipping."
  exit 0
fi

# ---------------------------------------------------------------------------
# Perform backup
# ---------------------------------------------------------------------------
log "INFO" "========== Harbor backup started at $(date '+%Y-%m-%d %H:%M:%S') =========="

BACKUP_START="$(date +%s)"

if restic backup "${HARBOR_DATA_DIR}" \
  --tag "harbor" \
  --tag "weekly" \
  --verbose >> "${LOG_FILE}" 2>&1; then

  BACKUP_END="$(date +%s)"
  BACKUP_DURATION=$(( BACKUP_END - BACKUP_START ))
  log "INFO" "Harbor registry backed up successfully (${BACKUP_DURATION}s)"
else
  log "ERROR" "Failed to back up Harbor registry"
  exit 1
fi

log "INFO" "========== Harbor backup finished =========="
exit 0
