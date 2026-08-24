#!/usr/bin/env bash
###############################################################################
# verify-integrity.sh - Furious Ducks Backup Integrity Verification
#
# Verifies Restic repository integrity, lists recent snapshots, and alerts
# if no snapshot was created within the last 26 hours.
#
# Required environment variables:
#   RESTIC_REPOSITORY  - Restic repository location
#   RESTIC_PASSWORD    - Restic repository password
#
# Optional environment variables:
#   WEBHOOK_URL        - URL for failure notifications
#   LOG_DIR            - Directory for log files (default: /var/log/backup)
#   MAX_AGE_HOURS      - Max acceptable snapshot age (default: 26)
###############################################################################
set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
WEBHOOK_URL="${WEBHOOK_URL:-https://chat.wk-archi-o24a-4-5-g1.fr/hooks/CHANGE_ME}"
LOG_DIR="${LOG_DIR:-/var/log/backup}"
LOG_FILE="${LOG_DIR}/verify-$(date +%Y%m%d-%H%M%S).log"
MAX_AGE_HOURS="${MAX_AGE_HOURS:-26}"

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
    --data "{\"text\":\"[BACKUP INTEGRITY ALERT] ${message}\"}" \
    "${WEBHOOK_URL}" >> "${LOG_FILE}" 2>&1 || \
    log "WARN" "Failed to send notification to webhook"
}

cleanup() {
  local exit_code=$?
  if [[ ${exit_code} -ne 0 ]]; then
    log "ERROR" "Integrity verification failed with exit code ${exit_code}"
    notify_failure "Integrity check exited with code ${exit_code}. Check ${LOG_FILE}."
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
# Step 1: Verify repository integrity
# ---------------------------------------------------------------------------
log "INFO" "========== Integrity Verification Started =========="
log "INFO" "Running restic check..."

if restic check >> "${LOG_FILE}" 2>&1; then
  log "INFO" "Repository integrity check PASSED"
else
  log "ERROR" "Repository integrity check FAILED"
  notify_failure "restic check failed - repository may be corrupted."
  exit 1
fi

# ---------------------------------------------------------------------------
# Step 2: List recent snapshots
# ---------------------------------------------------------------------------
log "INFO" "Listing recent snapshots..."

SNAPSHOTS_OUTPUT="$(restic snapshots --latest 10 2>&1)" || {
  log "ERROR" "Failed to list snapshots"
  exit 1
}

log "INFO" "Recent snapshots:"
echo "${SNAPSHOTS_OUTPUT}" | tee -a "${LOG_FILE}"

# ---------------------------------------------------------------------------
# Step 3: Verify snapshot freshness (at least one within MAX_AGE_HOURS)
# ---------------------------------------------------------------------------
log "INFO" "Checking for snapshots within the last ${MAX_AGE_HOURS} hours..."

# Query every snapshot: `--latest 1` returns one snapshot *per group*
# (host+paths), so the newest overall is the max of those timestamps, not the
# first one printed.
LATEST_SNAPSHOT_JSON="$(restic snapshots --json 2>/dev/null)" || {
  log "ERROR" "Failed to query latest snapshot"
  exit 1
}

# Check if any snapshots exist at all (the JSON is a single line, so count
# occurrences rather than matching lines)
SNAPSHOT_COUNT="$(echo "${LATEST_SNAPSHOT_JSON}" | grep -o '"time"' | wc -l)"

if [[ "${SNAPSHOT_COUNT}" -eq 0 ]]; then
  log "ERROR" "No snapshots found in the repository"
  notify_failure "No snapshots exist in the backup repository."
  exit 1
fi

# Extract the most recent snapshot time (RFC3339 sorts lexicographically)
LATEST_TIME="$(echo "${LATEST_SNAPSHOT_JSON}" | grep -o '"time":"[^"]*"' | cut -d'"' -f4 | sort | tail -1)"

if [[ -z "${LATEST_TIME}" ]]; then
  log "ERROR" "Could not parse latest snapshot time"
  exit 1
fi

# Convert to epoch seconds for comparison
LATEST_EPOCH="$(date -d "${LATEST_TIME}" +%s 2>/dev/null)" || {
  # Try alternate format (strip fractional seconds and timezone detail)
  CLEANED_TIME="$(echo "${LATEST_TIME}" | sed 's/\.[0-9]*//; s/+.*/Z/')"
  LATEST_EPOCH="$(date -d "${CLEANED_TIME}" +%s 2>/dev/null)" || {
    log "ERROR" "Could not parse snapshot timestamp '${LATEST_TIME}'"
    notify_failure "Freshness check could not parse snapshot timestamp '${LATEST_TIME}'."
    exit 1
  }
}

NOW_EPOCH="$(date +%s)"
AGE_SECONDS=$(( NOW_EPOCH - LATEST_EPOCH ))
AGE_HOURS=$(( AGE_SECONDS / 3600 ))
MAX_AGE_SECONDS=$(( MAX_AGE_HOURS * 3600 ))

if [[ ${AGE_SECONDS} -gt ${MAX_AGE_SECONDS} ]]; then
  log "ERROR" "Latest snapshot is ${AGE_HOURS} hours old (threshold: ${MAX_AGE_HOURS}h)"
  notify_failure "Latest backup snapshot is ${AGE_HOURS} hours old, exceeding the ${MAX_AGE_HOURS}-hour threshold."
  exit 1
fi

log "INFO" "Latest snapshot age: ${AGE_HOURS} hours (within ${MAX_AGE_HOURS}h threshold)"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
log "INFO" "========== Integrity Verification PASSED =========="
log "INFO" "Repository is healthy with recent snapshots"
exit 0
