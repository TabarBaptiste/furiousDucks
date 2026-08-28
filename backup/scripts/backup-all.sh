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
#   TEXTFILE_DIR       - node-exporter textfile dir for Prometheus metrics (default: /textfiles)
###############################################################################
set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
WEBHOOK_URL="${WEBHOOK_URL:-https://chat.wk-archi-o24a-4-5-g1.fr/hooks/CHANGE_ME}"
LOG_DIR="${LOG_DIR:-/var/log/backup}"
LOG_FILE="${LOG_DIR}/backup-$(date +%Y%m%d-%H%M%S).log"
TEXTFILE_DIR="${TEXTFILE_DIR:-/textfiles}"
# Volumes are mounted read-only under /backup-sources/ in the container
# (see docker-compose.backup.yml)
VOLUMES_BASE="${VOLUMES_BASE:-/backup-sources}"

# Directory (under /backup-sources) where logical DB dumps are written before
# being picked up by the volume backup loop below.
DB_DUMP_DIR="${DB_DUMP_DIR:-${VOLUMES_BASE}/db-dumps}"

# Volumes to back up: directory_name -> tag
declare -A BACKUP_TARGETS=(
  ["gitlab-config"]="gitlab-config"
  ["gitlab-data"]="gitlab-data"
  ["jenkins-data"]="jenkins"
  ["prometheus-data"]="prometheus"
  ["grafana-data"]="grafana"
  ["sonarqube-data"]="sonarqube"
  ["sonarqube-db-data"]="sonarqube-db"
  ["mattermost-data"]="mattermost"
  ["mattermost-config"]="mattermost-config"
  ["db-dumps"]="db-dumps"
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

# ---------------------------------------------------------------------------
# Logical database dumps (consistent point-in-time backups)
#
# A cold copy of a running database's data directory can be inconsistent.
# We take proper logical dumps via pg_dump over ci_network and write them
# into DB_DUMP_DIR, which the volume loop below then ships to Restic.
#
# NOTE: GitLab bundles its own PostgreSQL that is not reachable over the
# network; it is captured at volume level (gitlab-data). For a fully
# consistent GitLab backup, run `gitlab-backup create` inside the GitLab
# container on a schedule (out of scope for this container).
# ---------------------------------------------------------------------------
mkdir -p "${DB_DUMP_DIR}"

dump_postgres() {
  local name="$1" host="$2" user="$3" db="$4" pass="$5"
  local out="${DB_DUMP_DIR}/${name}.sql.gz"

  if [[ -z "${host}" || -z "${pass}" ]]; then
    log "WARN" "Skipping ${name} dump: host or password not configured"
    return 0
  fi

  log "INFO" "Dumping database '${db}' from ${host}..."
  if PGPASSWORD="${pass}" pg_dump -h "${host}" -U "${user}" -d "${db}" 2>> "${LOG_FILE}" | gzip > "${out}"; then
    log "INFO" "  -> ${name} dumped to ${out}"
  else
    log "ERROR" "  -> Failed to dump ${name} from ${host}"
    rm -f "${out}"
    FAILED=$(( FAILED + 1 ))
  fi
}

dump_postgres "sonarqube" "${SONARQUBE_DB_HOST:-}" "${SONARQUBE_DB_USER:-sonarqube}" \
  "${SONARQUBE_DB_NAME:-sonarqube}" "${SONARQUBE_DB_PASSWORD:-}"
dump_postgres "mattermost" "${MATTERMOST_DB_HOST:-}" "${MATTERMOST_DB_USER:-mattermost}" \
  "${MATTERMOST_DB_NAME:-mattermost}" "${MATTERMOST_DB_PASSWORD:-}"

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
    SUCCEEDED=$(( SUCCEEDED + 1 ))
  else
    log "ERROR" "  -> Failed to back up volume '${volume}'"
    FAILED=$(( FAILED + 1 ))
  fi
done

# ---------------------------------------------------------------------------
# Prometheus metrics (node-exporter textfile collector)
# Writes backup_last_status and backup_last_success_timestamp_seconds so that
# the BackupFailed / BackupTooOld alerts in Prometheus actually fire.
# ---------------------------------------------------------------------------
push_metrics() {
  local status="$1"   # 0 = success, 1 = failure
  local now
  now="$(date +%s)"

  if [[ ! -d "${TEXTFILE_DIR}" ]]; then
    log "WARN" "TEXTFILE_DIR '${TEXTFILE_DIR}' not found - Prometheus metrics not written"
    return 0
  fi

  # Write atomically: write to tmp then rename to avoid partial reads by node-exporter
  local tmp
  tmp="$(mktemp "${TEXTFILE_DIR}/backup.prom.XXXXXX")"
  cat > "${tmp}" <<PROM
# HELP backup_last_status Status of the last backup job (0=success, 1=failure)
# TYPE backup_last_status gauge
backup_last_status{job="restic"} ${status}
# HELP backup_last_success_timestamp_seconds Unix timestamp of the last successful backup
# TYPE backup_last_success_timestamp_seconds gauge
backup_last_success_timestamp_seconds{job="restic"} $( [[ "${status}" -eq 0 ]] && echo "${now}" || cat "${TEXTFILE_DIR}/backup_last_success_ts" 2>/dev/null || echo "0" )
PROM

  # Persist the success timestamp separately so failures don't erase it
  if [[ "${status}" -eq 0 ]]; then
    echo "${now}" > "${TEXTFILE_DIR}/backup_last_success_ts"
  fi

  # mktemp creates the file 0600 root; node-exporter runs as nobody and must
  # be able to read it, otherwise the metric never reaches Prometheus.
  chmod 0644 "${tmp}"
  mv "${tmp}" "${TEXTFILE_DIR}/backup.prom"
  log "INFO" "Prometheus metrics written to ${TEXTFILE_DIR}/backup.prom (status=${status})"
}

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
BACKUP_END="$(date +%s)"
TOTAL_DURATION=$(( BACKUP_END - BACKUP_START ))

log "INFO" "========== Backup finished at $(date '+%Y-%m-%d %H:%M:%S') =========="
log "INFO" "Duration: ${TOTAL_DURATION}s | Succeeded: ${SUCCEEDED} | Failed: ${FAILED}"

if [[ ${FAILED} -gt 0 ]]; then
  notify_failure "${FAILED} volume(s) failed to back up. Succeeded: ${SUCCEEDED}. Duration: ${TOTAL_DURATION}s."
  push_metrics 1
  exit 1
fi

log "INFO" "All backups completed successfully"
push_metrics 0
exit 0
