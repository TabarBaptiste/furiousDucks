#!/usr/bin/env bash
###############################################################################
# restore-volume.sh - Furious Ducks Volume Restore Script
#
# Restores a Restic snapshot into the live Docker volume it came from. Runs on
# the host (not inside backup-manager, which mounts every production volume
# read-only on purpose) and drives the whole sequence: extract, stop consumers,
# swap, restart.
#
# Usage:
#   ./restore-volume.sh                              List recent snapshots
#   ./restore-volume.sh <snapshot_id> <volume>       Restore into the live volume
#
# Parameters:
#   snapshot_id    Restic snapshot ID (short or full hash)
#   volume         Backup source name as shown in the snapshot paths, e.g.
#                  "jenkins-data" or "thetiptop-db-prod-data"
#
# Options:
#   --yes, -y        Skip the confirmation prompt (for unattended runs)
#   --keep-staging   Keep the extracted copy on disk after a successful restore
#   --help, -h       Show this help message
#
# Environment variables:
#   BACKUP_CONTAINER  backup-manager container (default: common-backup-manager-ci)
#   STAGING_DIR       Host staging directory (default: /var/tmp/furious-ducks-restore)
#   HELPER_IMAGE      Image used to write into the volume (default: alpine:3.20)
#   LOG_DIR           Directory for log files (default: /var/log/restore)
###############################################################################
set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
BACKUP_CONTAINER="${BACKUP_CONTAINER:-common-backup-manager-ci}"
STAGING_DIR="${STAGING_DIR:-/var/tmp/furious-ducks-restore}"
HELPER_IMAGE="${HELPER_IMAGE:-alpine:3.20}"
LOG_DIR="${LOG_DIR:-/var/log/restore}"
LOG_FILE="${LOG_DIR}/restore-volume-$(date +%Y%m%d-%H%M%S).log"

RUN_ID="$(date +%Y%m%d-%H%M%S)"
CONTAINER_STAGING="/tmp/manual-restore-${RUN_ID}"
HOST_STAGING="${STAGING_DIR}/${RUN_ID}"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
mkdir -p "${LOG_DIR}" 2>/dev/null || LOG_FILE=/dev/null

log() {
  local level="$1"; shift
  echo "$(date '+%Y-%m-%d %H:%M:%S') [${level}] $*" | tee -a "${LOG_FILE}"
}

usage() {
  sed -n '3,29p' "$0" | sed 's/^# \{0,1\}//'
  exit 0
}

# Leaves the extracted copy behind on failure: it is the only local trace of
# the snapshot once the volume has been touched.
SWAP_STARTED=0
CONTAINERS_STOPPED=0
SAFETY_ARCHIVE=""
DOCKER_VOLUME=""
RUNNING=()

cleanup() {
  local code=$?
  docker exec "${BACKUP_CONTAINER}" rm -rf "${CONTAINER_STAGING}" 2>/dev/null || true
  [[ ${code} -eq 0 ]] && return 0

  if [[ ${SWAP_STARTED} -eq 1 ]]; then
    log "ERROR" "Restore aborted (exit ${code}) MID-SWAP - '${DOCKER_VOLUME}' may be incomplete."
    log "ERROR" "Restore it from the safety archive: ${SAFETY_ARCHIVE}"
  else
    log "ERROR" "Restore aborted (exit ${code}). Volume untouched."
  fi

  # Never leave a service down because a later step failed.
  if [[ ${CONTAINERS_STOPPED} -eq 1 && ${#RUNNING[@]} -gt 0 ]]; then
    log "WARN" "Restarting ${RUNNING[*]} after failure"
    docker start "${RUNNING[@]}" >> "${LOG_FILE}" 2>&1 || \
      log "ERROR" "Could not restart ${RUNNING[*]} - start them manually"
  fi
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
# Parse arguments
# ---------------------------------------------------------------------------
SNAPSHOT_ID=""
VOLUME_KEY=""
ASSUME_YES=0
KEEP_STAGING=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage ;;
    --yes|-y) ASSUME_YES=1; shift ;;
    --keep-staging) KEEP_STAGING=1; shift ;;
    -*) log "ERROR" "Unknown option: $1"; exit 1 ;;
    *)
      if [[ -z "${SNAPSHOT_ID}" ]]; then SNAPSHOT_ID="$1"
      elif [[ -z "${VOLUME_KEY}" ]]; then VOLUME_KEY="$1"
      else log "ERROR" "Unexpected argument: $1"; exit 1
      fi
      shift ;;
  esac
done

# ---------------------------------------------------------------------------
# Pre-flight checks
# ---------------------------------------------------------------------------
if ! docker inspect "${BACKUP_CONTAINER}" > /dev/null 2>&1; then
  log "ERROR" "Container '${BACKUP_CONTAINER}' not found"
  exit 1
fi

if [[ -z "${SNAPSHOT_ID}" ]]; then
  log "INFO" "No snapshot ID provided. Listing recent snapshots..."
  docker exec "${BACKUP_CONTAINER}" /scripts/restore.sh
  echo "Usage: $(basename "$0") <snapshot_id> <volume>"
  exit 0
fi

if [[ -z "${VOLUME_KEY}" ]]; then
  log "ERROR" "Volume name is required"
  exit 1
fi

# ---------------------------------------------------------------------------
# Resolve the volume
#
# The mapping between a snapshot path (/backup-sources/<name>) and the real
# Docker volume is read from backup-manager's own mounts, so adding a project
# to the backup scope needs no change here.
# ---------------------------------------------------------------------------
MOUNT_LINE="$(docker inspect "${BACKUP_CONTAINER}" \
  --format '{{range .Mounts}}{{.Name}}|{{.Destination}}{{"\n"}}{{end}}' \
  | grep -E "\|/backup-sources/(projects/)?${VOLUME_KEY}$" || true)"

if [[ -z "${MOUNT_LINE}" ]]; then
  log "ERROR" "'${VOLUME_KEY}' is not a backed-up volume. Known volumes:"
  docker inspect "${BACKUP_CONTAINER}" \
    --format '{{range .Mounts}}{{.Destination}}{{"\n"}}{{end}}' \
    | sed -n 's#^/backup-sources/\(projects/\)\?#  - #p'
  exit 1
fi

DOCKER_VOLUME="${MOUNT_LINE%%|*}"
SNAPSHOT_PATH="${MOUNT_LINE#*|}"

if [[ -z "${DOCKER_VOLUME}" ]]; then
  log "ERROR" "'${VOLUME_KEY}' is a bind mount, not a Docker volume - restore it manually"
  exit 1
fi

# Containers using the volume must be stopped before it is swapped.
mapfile -t CONSUMERS < <(docker ps -a --filter "volume=${DOCKER_VOLUME}" --format '{{.Names}}' \
  | grep -vx "${BACKUP_CONTAINER}" || true)

mapfile -t RUNNING < <(docker ps --filter "volume=${DOCKER_VOLUME}" --format '{{.Names}}' \
  | grep -vx "${BACKUP_CONTAINER}" || true)

# ---------------------------------------------------------------------------
# Confirm
# ---------------------------------------------------------------------------
log "INFO" "========== Volume Restore =========="
log "INFO" "Snapshot:      ${SNAPSHOT_ID}"
log "INFO" "Snapshot path: ${SNAPSHOT_PATH}"
log "INFO" "Docker volume: ${DOCKER_VOLUME}"
log "INFO" "Consumers:     ${CONSUMERS[*]:-<none>}"
log "INFO" "To restart:    ${RUNNING[*]:-<none>}"

echo ""
echo "WARNING: the current content of '${DOCKER_VOLUME}' will be REPLACED."
echo "         A safety archive is written first, and the containers above are"
echo "         stopped during the swap."
echo ""

if [[ ${ASSUME_YES} -eq 0 ]]; then
  if [[ ! -t 0 ]]; then
    log "ERROR" "Not a terminal and --yes not given - refusing to overwrite"
    exit 1
  fi
  read -r -p "Continue? (y/N) " confirm
  if [[ "${confirm}" != "y" && "${confirm}" != "Y" ]]; then
    log "INFO" "Restore cancelled by user"
    exit 0
  fi
fi

# ---------------------------------------------------------------------------
# 1. Extract the snapshot inside backup-manager
# ---------------------------------------------------------------------------
log "INFO" "[1/6] Extracting snapshot ${SNAPSHOT_ID}..."
docker exec "${BACKUP_CONTAINER}" \
  /scripts/restore.sh "${SNAPSHOT_ID}" "${CONTAINER_STAGING}" --volume "${VOLUME_KEY}" \
  >> "${LOG_FILE}" 2>&1

# restic restores the full original path under the target directory.
EXTRACTED="${CONTAINER_STAGING}${SNAPSHOT_PATH}"

if ! docker exec "${BACKUP_CONTAINER}" test -d "${EXTRACTED}"; then
  log "ERROR" "Expected ${EXTRACTED} inside ${BACKUP_CONTAINER} - snapshot does not match this volume"
  exit 1
fi

FILE_COUNT="$(docker exec "${BACKUP_CONTAINER}" find "${EXTRACTED}" -type f 2>/dev/null | wc -l)"
if [[ "${FILE_COUNT}" -eq 0 ]]; then
  log "ERROR" "Extracted copy is empty - refusing to wipe the live volume"
  exit 1
fi
log "INFO" "  -> ${FILE_COUNT} file(s) extracted"

# ---------------------------------------------------------------------------
# 2. Optional inspection copy
#
# The swap itself streams tar straight from backup-manager (step 5): going
# through the host would rewrite every uid/gid to the calling user and break
# services that run under a fixed UID (Postgres, Redis).
# ---------------------------------------------------------------------------
if [[ ${KEEP_STAGING} -eq 1 ]]; then
  log "INFO" "[2/6] Saving an inspection copy to ${HOST_STAGING}..."
  mkdir -p "${HOST_STAGING}"
  docker cp "${BACKUP_CONTAINER}:${EXTRACTED}/." "${HOST_STAGING}/" >> "${LOG_FILE}" 2>&1
  log "INFO" "  -> $(du -sh "${HOST_STAGING}" 2>/dev/null | cut -f1) copied (ownership not preserved)"
else
  log "INFO" "[2/6] Skipping host copy (--keep-staging not given)"
fi

# ---------------------------------------------------------------------------
# 3. Stop consumers
#
# Before the safety archive, so that archive is a cold copy: archiving a
# running service would capture the same inconsistent state it exists to guard
# against.
# ---------------------------------------------------------------------------
if [[ ${#RUNNING[@]} -gt 0 ]]; then
  log "INFO" "[3/6] Stopping ${RUNNING[*]}..."
  docker stop "${RUNNING[@]}" >> "${LOG_FILE}" 2>&1
  CONTAINERS_STOPPED=1
else
  log "INFO" "[3/6] No running consumer to stop"
fi

# ---------------------------------------------------------------------------
# 4. Safety archive of the current volume
# ---------------------------------------------------------------------------
SAFETY_ARCHIVE="${STAGING_DIR}/${DOCKER_VOLUME}-before-${RUN_ID}.tar.gz"
log "INFO" "[4/6] Archiving current volume to ${SAFETY_ARCHIVE}..."
mkdir -p "${STAGING_DIR}"
docker run --rm \
  -v "${DOCKER_VOLUME}:/source:ro" \
  -v "${STAGING_DIR}:/out" \
  "${HELPER_IMAGE}" \
  tar czf "/out/$(basename "${SAFETY_ARCHIVE}")" -C /source . >> "${LOG_FILE}" 2>&1
log "INFO" "  -> $(du -sh "${SAFETY_ARCHIVE}" 2>/dev/null | cut -f1) archived"

# ---------------------------------------------------------------------------
# 5. Swap the volume content
#
# tar is streamed container-to-container so uid/gid survive the transfer, and
# a helper container writes the volume so the script needs no root on the host.
# ---------------------------------------------------------------------------
log "INFO" "[5/6] Replacing content of ${DOCKER_VOLUME}..."
SWAP_STARTED=1
docker exec "${BACKUP_CONTAINER}" tar cf - -C "${EXTRACTED}" . \
  | docker run --rm -i \
      -v "${DOCKER_VOLUME}:/target" \
      "${HELPER_IMAGE}" \
      sh -c 'find /target -mindepth 1 -delete && tar xf - -C /target'
log "INFO" "  -> content replaced"

# ---------------------------------------------------------------------------
# 6. Restart consumers
# ---------------------------------------------------------------------------
if [[ ${#RUNNING[@]} -gt 0 ]]; then
  log "INFO" "[6/6] Restarting ${RUNNING[*]}..."
  docker start "${RUNNING[@]}" >> "${LOG_FILE}" 2>&1
else
  log "INFO" "[6/6] Nothing to restart"
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
RESTORED_COUNT="$(docker run --rm -v "${DOCKER_VOLUME}:/target:ro" "${HELPER_IMAGE}" \
  find /target -type f 2>/dev/null | wc -l)"

if [[ ${KEEP_STAGING} -eq 0 ]]; then
  rm -rf "${HOST_STAGING}"
fi

log "INFO" "========== Restore Complete =========="
log "INFO" "Volume:          ${DOCKER_VOLUME}"
log "INFO" "Files in volume: ${RESTORED_COUNT}"
log "INFO" "Safety archive:  ${SAFETY_ARCHIVE}"
echo ""
echo "Check the restarted service before deleting the safety archive."
echo "To roll back this restore:"
echo "  docker stop ${RUNNING[*]:-<consumers>}"
echo "  docker run --rm -v ${DOCKER_VOLUME}:/target -v ${STAGING_DIR}:/in:ro ${HELPER_IMAGE} \\"
echo "    sh -c 'find /target -mindepth 1 -delete && tar xzf /in/$(basename "${SAFETY_ARCHIVE}") -C /target'"
echo "  docker start ${RUNNING[*]:-<consumers>}"
exit 0
