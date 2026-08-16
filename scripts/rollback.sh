#!/usr/bin/env bash
###############################################################################
# rollback.sh - Furious Ducks Rollback Script
#
# Rolls back a deployed application to a previous version. Supports DEV,
# PREPROD (simple image swap) and PROD (Blue/Green via Traefik).
#
# Usage:
#   ./rollback.sh <PROJECT_NAME> <ENVIRONMENT> [PREVIOUS_TAG] [--with-db]
#
# Parameters:
#   PROJECT_NAME   Application name
#   ENVIRONMENT    Target environment: dev | preprod | prod
#   PREVIOUS_TAG   Image tag to roll back to (optional, auto-detected if omitted)
#
# Options:
#   --with-db      Also rollback the database (requires DB backup).
#                  PostgreSQL only for now (only DB engine used by
#                  the current pilot project, The Tip Top).
#   --help, -h     Show help message
#
# Required environment variables:
#   REGISTRY       Harbor registry (default: registry.wk-archi-o24a-4-5-g1.fr)
###############################################################################
set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
REGISTRY="${REGISTRY:-registry.wk-archi-o24a-4-5-g1.fr}"
HARBOR_PROJECT="${HARBOR_PROJECT:-furious-ducks}"
NETWORK="${NETWORK:-workflow-network}"
HEALTH_PATH="${HEALTH_PATH:-/health}"
LOG_DIR="${LOG_DIR:-/var/log/deploy}"
LOG_FILE="${LOG_DIR}/rollback-$(date +%Y%m%d-%H%M%S).log"

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

# Probe a container's health endpoint from a throwaway curl sidecar on the same
# Docker network, rather than reading `.State.Health.Status`. Two reasons: the
# containers created by deployBlueGreen.groovy carry no --health-cmd at all, so
# that status stays empty forever; and client images are expected to be minimal
# (see the cahier des spécifications) and cannot be assumed to ship curl, so an
# in-container health command could never report healthy either. Both rollback
# paths below used to wait on that status and always timed out.
wait_for_health() {
  local container="$1"
  local port="$2"
  local timeout="${3:-60}"
  local start
  start="$(date +%s)"

  while (( $(date +%s) - start < timeout )); do
    if docker run --rm --network "${NETWORK}" curlimages/curl:latest \
        -sf "http://${container}:${port}${HEALTH_PATH}" >> "${LOG_FILE}" 2>&1; then
      return 0
    fi
    sleep 5
  done
  return 1
}

usage() {
  cat <<USAGE
Usage: $(basename "$0") <PROJECT_NAME> <ENVIRONMENT> [PREVIOUS_TAG] [--with-db]

Arguments:
  PROJECT_NAME   Application name (e.g., furious-ducks-app)
  ENVIRONMENT    Target environment: dev | preprod | prod
  PREVIOUS_TAG   Image tag to roll back to (optional; auto-detected if omitted)

Options:
  --with-db      Also rollback the database using the latest backup
  --help, -h     Show this help message

Examples:
  $(basename "$0") furious-ducks dev v1.1.0
  $(basename "$0") furious-ducks prod v1.1.0 --with-db
  $(basename "$0") furious-ducks preprod           # auto-detect previous tag
USAGE
  exit 0
}

# ---------------------------------------------------------------------------
# Parse arguments
# ---------------------------------------------------------------------------
PROJECT_NAME=""
ENVIRONMENT=""
PREVIOUS_TAG=""
WITH_DB=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h)
      usage
      ;;
    --with-db)
      WITH_DB=true
      shift
      ;;
    *)
      if [[ -z "${PROJECT_NAME}" ]]; then
        PROJECT_NAME="$1"
      elif [[ -z "${ENVIRONMENT}" ]]; then
        ENVIRONMENT="$1"
      elif [[ -z "${PREVIOUS_TAG}" ]]; then
        PREVIOUS_TAG="$1"
      else
        log "ERROR" "Unexpected argument: $1"
        usage
      fi
      shift
      ;;
  esac
done

if [[ -z "${PROJECT_NAME}" || -z "${ENVIRONMENT}" ]]; then
  log "ERROR" "PROJECT_NAME and ENVIRONMENT are required"
  usage
fi

# Validate environment
ENVIRONMENT="$(echo "${ENVIRONMENT}" | tr '[:upper:]' '[:lower:]')"
if [[ ! "${ENVIRONMENT}" =~ ^(dev|preprod|prod)$ ]]; then
  log "ERROR" "Invalid environment '${ENVIRONMENT}'. Must be: dev, preprod, prod"
  exit 1
fi

# ---------------------------------------------------------------------------
# Determine container name based on environment
# ---------------------------------------------------------------------------
case "${ENVIRONMENT}" in
  dev)     CONTAINER_NAME="${PROJECT_NAME}-dev" ;;
  preprod) CONTAINER_NAME="${PROJECT_NAME}-preprod" ;;
  prod)    CONTAINER_NAME="${PROJECT_NAME}-prod-blue" ;;
esac

log "INFO" "========== Rollback Started =========="
log "INFO" "Project:     ${PROJECT_NAME}"
log "INFO" "Environment: ${ENVIRONMENT}"
log "INFO" "Container:   ${CONTAINER_NAME}"
log "INFO" "With DB:     ${WITH_DB}"

# ---------------------------------------------------------------------------
# Auto-detect previous tag if not provided
# ---------------------------------------------------------------------------
if [[ -z "${PREVIOUS_TAG}" ]]; then
  log "INFO" "No previous tag specified. Attempting auto-detection..."

  CURRENT_IMAGE="$(docker inspect --format='{{.Config.Image}}' "${CONTAINER_NAME}" 2>/dev/null || echo '')"

  if [[ -z "${CURRENT_IMAGE}" ]]; then
    log "ERROR" "Cannot auto-detect: container '${CONTAINER_NAME}' not found"
    log "ERROR" "Please provide PREVIOUS_TAG explicitly"
    exit 1
  fi

  # Try to find previous tag from deploy labels
  PREVIOUS_TAG="$(docker inspect --format='{{index .Config.Labels "deploy.previous-tag"}}' "${CONTAINER_NAME}" 2>/dev/null || echo '')"

  if [[ -z "${PREVIOUS_TAG}" || "${PREVIOUS_TAG}" == "<no value>" ]]; then
    log "ERROR" "Cannot auto-detect previous tag. No 'deploy.previous-tag' label found."
    log "ERROR" "Please provide PREVIOUS_TAG explicitly:"
    log "ERROR" "  $(basename "$0") ${PROJECT_NAME} ${ENVIRONMENT} <TAG>"
    exit 1
  fi

  log "INFO" "Auto-detected previous tag: ${PREVIOUS_TAG}"
fi

ROLLBACK_IMAGE="${REGISTRY}/${HARBOR_PROJECT}/${PROJECT_NAME}:${PREVIOUS_TAG}"
log "INFO" "Rollback image: ${ROLLBACK_IMAGE}"

# ---------------------------------------------------------------------------
# Pull the rollback image
# ---------------------------------------------------------------------------
log "INFO" "Pulling rollback image..."

if docker pull "${ROLLBACK_IMAGE}" >> "${LOG_FILE}" 2>&1; then
  log "INFO" "Image pulled successfully"
else
  log "ERROR" "Failed to pull image ${ROLLBACK_IMAGE}"
  exit 1
fi

# ---------------------------------------------------------------------------
# Database rollback (if requested)
# ---------------------------------------------------------------------------
if [[ "${WITH_DB}" == true ]]; then
  log "INFO" "Database rollback requested..."

  DB_CONTAINER="${PROJECT_NAME}-db-${ENVIRONMENT}"
  DB_BACKUP_DIR="/var/backups/${PROJECT_NAME}/db"

  if [[ ! -d "${DB_BACKUP_DIR}" ]]; then
    log "ERROR" "Database backup directory not found: ${DB_BACKUP_DIR}"
    log "ERROR" "Cannot perform database rollback without backup"
    exit 1
  fi

  # Find the latest database backup
  LATEST_DB_BACKUP="$(ls -t "${DB_BACKUP_DIR}"/*.sql.gz 2>/dev/null | head -1 || echo '')"

  if [[ -z "${LATEST_DB_BACKUP}" ]]; then
    log "ERROR" "No database backups found in ${DB_BACKUP_DIR}"
    exit 1
  fi

  log "INFO" "Using database backup: ${LATEST_DB_BACKUP}"

  # Check if database container exists
  if ! docker ps -q -f "name=${DB_CONTAINER}" | grep -q .; then
    log "ERROR" "Database container '${DB_CONTAINER}' is not running"
    exit 1
  fi

  # Determine database type and restore
  DB_TYPE="$(docker inspect --format='{{index .Config.Labels "db.type"}}' "${DB_CONTAINER}" 2>/dev/null || echo 'postgres')"

  log "INFO" "Restoring ${DB_TYPE} database from backup..."

  case "${DB_TYPE}" in
    postgres|postgresql)
      DB_NAME="$(docker inspect --format='{{index .Config.Env}}' "${DB_CONTAINER}" 2>/dev/null | grep -o 'POSTGRES_DB=[^ ]*' | cut -d= -f2 || echo "${PROJECT_NAME}")"
      zcat "${LATEST_DB_BACKUP}" | docker exec -i "${DB_CONTAINER}" psql -U postgres -d "${DB_NAME}" >> "${LOG_FILE}" 2>&1 || {
        log "ERROR" "Database restore failed"
        exit 1
      }
      ;;
    *)
      log "ERROR" "Unsupported database type: ${DB_TYPE}"
      exit 1
      ;;
  esac

  log "INFO" "Database restored successfully"
fi

# ---------------------------------------------------------------------------
# Application rollback (environment-specific)
# ---------------------------------------------------------------------------
case "${ENVIRONMENT}" in
  # -----------------------------------------------------------------------
  # DEV / PREPROD: Simple container replacement
  # -----------------------------------------------------------------------
  dev|preprod)
    log "INFO" "Performing simple rollback for ${ENVIRONMENT}..."

    # The DEV/PREPROD router name matches the container name (see deployContainer
    # in ciPipeline.groovy). Recover the Traefik routing config from the running
    # container so the rollback container stays reachable through Traefik.
    ROUTER_NAME="${CONTAINER_NAME}"
    DOMAIN="$(docker inspect --format='{{index .Config.Labels "traefik.http.routers.'"${ROUTER_NAME}"'.rule"}}' "${CONTAINER_NAME}" 2>/dev/null || echo '')"
    DOMAIN="$(echo "${DOMAIN}" | grep -o '`[^`]*`' | tr -d '`' || echo '')"
    APP_PORT="$(docker inspect --format='{{index .Config.Labels "traefik.http.services.'"${ROUTER_NAME}"'.loadbalancer.server.port"}}' "${CONTAINER_NAME}" 2>/dev/null || echo '')"

    if [[ -z "${DOMAIN}" || -z "${APP_PORT}" ]]; then
      log "ERROR" "Could not recover Traefik domain/port from container '${CONTAINER_NAME}' labels"
      log "ERROR" "The rollback container would not be routed by Traefik. Aborting."
      exit 1
    fi
    log "INFO" "Recovered routing - domain: ${DOMAIN}, port: ${APP_PORT}"

    NODE_ENV_VALUE="$( [[ "${ENVIRONMENT}" == "prod" ]] && echo 'production' || echo "${ENVIRONMENT}" )"

    # Save current image tag for potential re-rollback
    CURRENT_TAG="$(docker inspect --format='{{index .Config.Labels "deploy.tag"}}' "${CONTAINER_NAME}" 2>/dev/null || echo 'unknown')"

    log "INFO" "Stopping current container (tag: ${CURRENT_TAG})..."
    docker stop "${CONTAINER_NAME}" >> "${LOG_FILE}" 2>&1 || true

    # Rename current container as backup
    BACKUP_CONTAINER="${CONTAINER_NAME}-backup-$(date +%s)"
    docker rename "${CONTAINER_NAME}" "${BACKUP_CONTAINER}" >> "${LOG_FILE}" 2>&1 || {
      docker rm -f "${CONTAINER_NAME}" >> "${LOG_FILE}" 2>&1 || true
    }

    log "INFO" "Starting rollback container..."
    docker run -d \
      --name "${CONTAINER_NAME}" \
      --network "${NETWORK}" \
      --restart unless-stopped \
      --label "traefik.enable=true" \
      --label "traefik.http.routers.${ROUTER_NAME}.rule=Host(\`${DOMAIN}\`)" \
      --label "traefik.http.routers.${ROUTER_NAME}.entrypoints=websecure" \
      --label "traefik.http.routers.${ROUTER_NAME}.tls.certresolver=letsencrypt" \
      --label "traefik.http.services.${ROUTER_NAME}.loadbalancer.server.port=${APP_PORT}" \
      --label "traefik.http.routers.${ROUTER_NAME}.middlewares=secure-headers@file" \
      --label "deploy.project=${PROJECT_NAME}" \
      --label "deploy.tag=${PREVIOUS_TAG}" \
      --label "deploy.previous-tag=${CURRENT_TAG}" \
      --label "deploy.environment=${ENVIRONMENT}" \
      --label "deploy.rollback=true" \
      --label "deploy.timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
      -e NODE_ENV="${NODE_ENV_VALUE}" \
      -e APP_ENV="${ENVIRONMENT}" \
      "${ROLLBACK_IMAGE}" >> "${LOG_FILE}" 2>&1

    if [[ $? -ne 0 ]]; then
      log "ERROR" "Failed to start rollback container. Attempting to restore backup..."
      docker rename "${BACKUP_CONTAINER}" "${CONTAINER_NAME}" >> "${LOG_FILE}" 2>&1 || true
      docker start "${CONTAINER_NAME}" >> "${LOG_FILE}" 2>&1 || true
      exit 1
    fi

    # Verify container is running
    sleep 5
    if docker ps -q -f "name=${CONTAINER_NAME}" -f "status=running" | grep -q .; then
      log "INFO" "Rollback container is running"
      # Clean up backup container
      docker rm -f "${BACKUP_CONTAINER}" >> "${LOG_FILE}" 2>&1 || true
    else
      log "ERROR" "Rollback container failed to start. Restoring previous version..."
      docker rm -f "${CONTAINER_NAME}" >> "${LOG_FILE}" 2>&1 || true
      docker rename "${BACKUP_CONTAINER}" "${CONTAINER_NAME}" >> "${LOG_FILE}" 2>&1 || true
      docker start "${CONTAINER_NAME}" >> "${LOG_FILE}" 2>&1 || true
      exit 1
    fi
    ;;

  # -----------------------------------------------------------------------
  # PROD: Blue/Green rollback via Traefik label swap
  # -----------------------------------------------------------------------
  prod)
    log "INFO" "Performing Blue/Green rollback for PROD..."

    BLUE_NAME="${PROJECT_NAME}-${ENVIRONMENT}-blue"
    GREEN_NAME="${PROJECT_NAME}-${ENVIRONMENT}-green"
    ROUTER_NAME="${PROJECT_NAME}-${ENVIRONMENT}"
    PAUSED_NAME="${BLUE_NAME}-previous"

    # Get the current domain from BLUE container Traefik labels
    DOMAIN="$(docker inspect --format='{{index .Config.Labels "traefik.http.routers.'"${ROUTER_NAME}"'.rule"}}' "${BLUE_NAME}" 2>/dev/null || echo '')"
    # Extract domain from Host(`...`) rule
    DOMAIN="$(echo "${DOMAIN}" | grep -o '`[^`]*`' | tr -d '`' || echo '')"
    APP_PORT="$(docker inspect --format='{{index .Config.Labels "traefik.http.services.'"${ROUTER_NAME}"'.loadbalancer.server.port"}}' "${BLUE_NAME}" 2>/dev/null || echo '8080')"

    if [[ -z "${DOMAIN}" ]]; then
      log "ERROR" "Could not determine domain from BLUE container labels"
      log "ERROR" "Please check container '${BLUE_NAME}' Traefik configuration"
      exit 1
    fi

    log "INFO" "Domain: ${DOMAIN}, Port: ${APP_PORT}"

    # -----------------------------------------------------------------------
    # Fast path: a paused standby from the previous deploy is on standby
    # (deployBlueGreen.groovy keeps one generation back instead of deleting
    # it). Reuse it directly instead of pulling from the registry.
    # -----------------------------------------------------------------------
    USE_FAST_PATH=false
    if docker ps -aq -f "name=${PAUSED_NAME}" | grep -q .; then
      PAUSED_TAG="$(docker inspect --format='{{index .Config.Labels "deploy.tag"}}' "${PAUSED_NAME}" 2>/dev/null || echo '')"
      if [[ "${PAUSED_TAG}" == "${PREVIOUS_TAG}" ]]; then
        USE_FAST_PATH=true
      else
        log "INFO" "Paused standby '${PAUSED_NAME}' holds tag '${PAUSED_TAG}', not the requested '${PREVIOUS_TAG}' - falling back to registry pull"
      fi
    fi

    if [[ "${USE_FAST_PATH}" == true ]]; then
      log "INFO" "Found paused standby '${PAUSED_NAME}' (tag: ${PAUSED_TAG}) - restarting it instead of pulling from the registry"
      docker start "${PAUSED_NAME}" >> "${LOG_FILE}" 2>&1

      if ! wait_for_health "${PAUSED_NAME}" "${APP_PORT}" 60; then
        log "ERROR" "Paused standby failed to become healthy - rollback aborted"
        log "ERROR" "BLUE container left untouched and still serving traffic"
        docker stop "${PAUSED_NAME}" >> "${LOG_FILE}" 2>&1 || true
        exit 1
      fi

      log "INFO" "Standby is healthy (already carries the Traefik labels) - switching over..."
      docker stop "${BLUE_NAME}" >> "${LOG_FILE}" 2>&1 || true
      docker rm -f "${BLUE_NAME}" >> "${LOG_FILE}" 2>&1 || true
      docker rename "${PAUSED_NAME}" "${BLUE_NAME}" >> "${LOG_FILE}" 2>&1

      log "INFO" "Traefik routing switched to the paused standby"
    else
      # -----------------------------------------------------------------
      # Slow path: no usable standby, pull the rollback image and rebuild
      # -----------------------------------------------------------------

      # Remove any leftover GREEN container
      if docker ps -aq -f "name=${GREEN_NAME}" | grep -q .; then
        docker rm -f "${GREEN_NAME}" >> "${LOG_FILE}" 2>&1 || true
      fi

      # Start new GREEN container with the rollback image and Traefik labels
      log "INFO" "Starting GREEN container with rollback image..."
      docker run -d \
        --name "${GREEN_NAME}" \
        --network "${NETWORK}" \
        --restart unless-stopped \
        --label "traefik.enable=false" \
        --label "deploy.project=${PROJECT_NAME}" \
        --label "deploy.role=green" \
        --label "deploy.tag=${PREVIOUS_TAG}" \
        --label "deploy.rollback=true" \
        --label "deploy.timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
        "${ROLLBACK_IMAGE}" >> "${LOG_FILE}" 2>&1

      # Wait for GREEN health check
      log "INFO" "Waiting for GREEN health check..."
      if ! wait_for_health "${GREEN_NAME}" "${APP_PORT}" 120; then
        log "ERROR" "GREEN health check timed out"
        docker rm -f "${GREEN_NAME}" >> "${LOG_FILE}" 2>&1 || true
        log "ERROR" "BLUE container retained - rollback aborted"
        exit 1
      fi

      log "INFO" "GREEN is healthy. Switching Traefik routing..."

      # Stop BLUE (releases Traefik route)
      docker stop "${BLUE_NAME}" >> "${LOG_FILE}" 2>&1 || true

      # Recreate GREEN with Traefik routing enabled
      docker stop "${GREEN_NAME}" >> "${LOG_FILE}" 2>&1
      docker rm "${GREEN_NAME}" >> "${LOG_FILE}" 2>&1

      docker run -d \
        --name "${GREEN_NAME}" \
        --network "${NETWORK}" \
        --restart unless-stopped \
        --label "traefik.enable=true" \
        --label "traefik.http.routers.${ROUTER_NAME}.rule=Host(\`${DOMAIN}\`)" \
        --label "traefik.http.routers.${ROUTER_NAME}.entrypoints=websecure" \
        --label "traefik.http.routers.${ROUTER_NAME}.tls.certresolver=letsencrypt" \
        --label "traefik.http.services.${ROUTER_NAME}.loadbalancer.server.port=${APP_PORT}" \
        --label "traefik.http.routers.${ROUTER_NAME}.middlewares=secure-headers@file" \
        --label "deploy.project=${PROJECT_NAME}" \
        --label "deploy.role=green" \
        --label "deploy.tag=${PREVIOUS_TAG}" \
        --label "deploy.rollback=true" \
        --label "deploy.timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
        "${ROLLBACK_IMAGE}" >> "${LOG_FILE}" 2>&1

      # Wait for healthy again
      if ! wait_for_health "${GREEN_NAME}" "${APP_PORT}" 120; then
        log "ERROR" "GREEN failed to become healthy after routing switch"
        docker rm -f "${GREEN_NAME}" >> "${LOG_FILE}" 2>&1 || true
        docker start "${BLUE_NAME}" >> "${LOG_FILE}" 2>&1 || true
        log "ERROR" "Restored BLUE container"
        exit 1
      fi

      # Remove old BLUE and rename GREEN to BLUE
      docker rm -f "${BLUE_NAME}" >> "${LOG_FILE}" 2>&1 || true
      docker rename "${GREEN_NAME}" "${BLUE_NAME}" >> "${LOG_FILE}" 2>&1

      log "INFO" "Traefik routing switched to rollback version"
    fi
    ;;
esac

# ---------------------------------------------------------------------------
# Verification
# ---------------------------------------------------------------------------
log "INFO" "Verifying rollback..."

RUNNING="$(docker inspect --format='{{.State.Status}}' "${CONTAINER_NAME}" 2>/dev/null || echo 'not found')"
DEPLOYED_IMAGE="$(docker inspect --format='{{.Config.Image}}' "${CONTAINER_NAME}" 2>/dev/null || echo 'unknown')"

if [[ "${RUNNING}" == "running" ]]; then
  log "INFO" "Container '${CONTAINER_NAME}' is running"
  log "INFO" "Image: ${DEPLOYED_IMAGE}"
else
  log "ERROR" "Container '${CONTAINER_NAME}' is NOT running (status: ${RUNNING})"
  exit 1
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
log "INFO" "========== Rollback Complete =========="
log "INFO" "Project:     ${PROJECT_NAME}"
log "INFO" "Environment: ${ENVIRONMENT}"
log "INFO" "Rolled to:   ${PREVIOUS_TAG}"
log "INFO" "Image:       ${ROLLBACK_IMAGE}"
log "INFO" "DB Rollback: ${WITH_DB}"
log "INFO" "Status:      SUCCESS"
exit 0
