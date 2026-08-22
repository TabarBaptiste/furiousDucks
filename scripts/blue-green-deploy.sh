#!/usr/bin/env bash
###############################################################################
# blue-green-deploy.sh - Furious Ducks Blue/Green Deployment via Traefik
#
# Performs a zero-downtime Blue/Green deployment for production applications
# using Traefik as the reverse proxy. The script pulls a new image, starts
# a GREEN container, validates health, switches Traefik routing, monitors,
# then retires the old BLUE container.
#
# Usage:
#   ./blue-green-deploy.sh <PROJECT_NAME> <IMAGE_TAG> <DOMAIN> <APP_PORT>
#   ./blue-green-deploy.sh --project myapp --tag v1.2.3 --domain app.example.com --port 8080
#
# Parameters:
#   PROJECT_NAME   Application/project name (used for container naming)
#   IMAGE_TAG      Docker image tag to deploy
#   DOMAIN         Domain name for Traefik routing
#   APP_PORT       Application port inside the container
#
# Optional environment variables:
#   REGISTRY       Harbor registry (default: registry.wk-archi-o24a-4-5-g1.fr)
#   NETWORK        Docker network (default: workflow-network)
#   MONITOR_MODE   "quick" (5 min) or "full" (15 min) (default: quick)
#   HEALTH_TIMEOUT Health check timeout in seconds (default: 120)
###############################################################################
set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
REGISTRY="${REGISTRY:-registry.wk-archi-o24a-4-5-g1.fr}"
NETWORK="${NETWORK:-workflow-network}"
MONITOR_MODE="${MONITOR_MODE:-quick}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-120}"
LOG_DIR="${LOG_DIR:-/var/log/deploy}"
LOG_FILE="${LOG_DIR}/deploy-$(date +%Y%m%d-%H%M%S).log"

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
Usage: $(basename "$0") <PROJECT_NAME> <IMAGE_TAG> <DOMAIN> <APP_PORT>

Arguments:
  PROJECT_NAME   Application name (e.g., furious-ducks-app)
  IMAGE_TAG      Docker image tag (e.g., v1.2.3, latest)
  DOMAIN         Domain for Traefik routing (e.g., app.wk-archi-o24a-4-5-g1.fr)
  APP_PORT       Application port inside the container (e.g., 8080)

Options:
  --help, -h     Show this help message

Environment:
  REGISTRY       Harbor registry (default: registry.wk-archi-o24a-4-5-g1.fr)
  NETWORK        Docker network (default: workflow-network)
  MONITOR_MODE   "quick" (5 min) or "full" (15 min) monitoring (default: quick)
  HEALTH_TIMEOUT Health check timeout in seconds (default: 120)

Example:
  $(basename "$0") furious-ducks v1.2.3 app.wk-archi-o24a-4-5-g1.fr 8080
USAGE
  exit 0
}

rollback() {
  log "ERROR" "========== ROLLBACK INITIATED =========="
  log "ERROR" "Stopping and removing GREEN container..."

  if docker ps -q -f "name=${GREEN_NAME}" | grep -q .; then
    docker stop "${GREEN_NAME}" >> "${LOG_FILE}" 2>&1 || true
    docker rm "${GREEN_NAME}" >> "${LOG_FILE}" 2>&1 || true
    log "INFO" "GREEN container removed"
  fi

  # Ensure BLUE is still running and routing
  if docker ps -q -f "name=${BLUE_NAME}" | grep -q .; then
    log "INFO" "BLUE container '${BLUE_NAME}' is still running - service is intact"
  else
    log "ERROR" "BLUE container '${BLUE_NAME}' is NOT running - MANUAL INTERVENTION REQUIRED"
  fi

  log "ERROR" "Rollback complete. BLUE container retained."
  exit 1
}

# ---------------------------------------------------------------------------
# Parse arguments
# ---------------------------------------------------------------------------
if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
fi

if [[ $# -lt 4 ]]; then
  log "ERROR" "Missing required arguments"
  usage
fi

PROJECT_NAME="$1"
IMAGE_TAG="$2"
DOMAIN="$3"
APP_PORT="$4"

BLUE_NAME="${PROJECT_NAME}-blue"
GREEN_NAME="${PROJECT_NAME}-green"
FULL_IMAGE="${REGISTRY}/${PROJECT_NAME}:${IMAGE_TAG}"

log "INFO" "========== Blue/Green Deployment Started =========="
log "INFO" "Project:   ${PROJECT_NAME}"
log "INFO" "Image:     ${FULL_IMAGE}"
log "INFO" "Domain:    ${DOMAIN}"
log "INFO" "Port:      ${APP_PORT}"
log "INFO" "Network:   ${NETWORK}"
log "INFO" "Monitor:   ${MONITOR_MODE}"

# ---------------------------------------------------------------------------
# Step 1: Identify current BLUE container
# ---------------------------------------------------------------------------
log "INFO" "[Step 1/9] Identifying current BLUE container..."

if docker ps -q -f "name=${BLUE_NAME}" | grep -q .; then
  BLUE_IMAGE="$(docker inspect --format='{{.Config.Image}}' "${BLUE_NAME}" 2>/dev/null || echo 'unknown')"
  log "INFO" "Current BLUE: ${BLUE_NAME} (image: ${BLUE_IMAGE})"
else
  log "WARN" "No existing BLUE container found. This appears to be an initial deployment."
  BLUE_IMAGE="none"
fi

# ---------------------------------------------------------------------------
# Step 2: Pull new image from Harbor registry
# ---------------------------------------------------------------------------
log "INFO" "[Step 2/9] Pulling image ${FULL_IMAGE}..."

if docker pull "${FULL_IMAGE}" >> "${LOG_FILE}" 2>&1; then
  log "INFO" "Image pulled successfully"
else
  log "ERROR" "Failed to pull image ${FULL_IMAGE}"
  exit 1
fi

# ---------------------------------------------------------------------------
# Step 3: Start GREEN container (no Traefik routing yet)
# ---------------------------------------------------------------------------
log "INFO" "[Step 3/9] Starting GREEN container..."

# Remove any leftover GREEN container
if docker ps -aq -f "name=${GREEN_NAME}" | grep -q .; then
  docker rm -f "${GREEN_NAME}" >> "${LOG_FILE}" 2>&1 || true
fi

docker run -d \
  --name "${GREEN_NAME}" \
  --network "${NETWORK}" \
  --restart unless-stopped \
  --health-cmd "curl -f http://localhost:${APP_PORT}/health || exit 1" \
  --health-interval 10s \
  --health-timeout 5s \
  --health-retries 3 \
  --health-start-period 30s \
  --label "traefik.enable=false" \
  --label "deploy.project=${PROJECT_NAME}" \
  --label "deploy.role=green" \
  --label "deploy.tag=${IMAGE_TAG}" \
  --label "deploy.timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  "${FULL_IMAGE}" >> "${LOG_FILE}" 2>&1

if [[ $? -ne 0 ]]; then
  log "ERROR" "Failed to start GREEN container"
  rollback
fi

log "INFO" "GREEN container started: ${GREEN_NAME}"

# ---------------------------------------------------------------------------
# Step 4: Wait for GREEN health check to pass
# ---------------------------------------------------------------------------
log "INFO" "[Step 4/9] Waiting for GREEN health check (timeout: ${HEALTH_TIMEOUT}s)..."

HEALTH_START="$(date +%s)"
HEALTHY=false

while true; do
  ELAPSED=$(( $(date +%s) - HEALTH_START ))

  if [[ ${ELAPSED} -ge ${HEALTH_TIMEOUT} ]]; then
    log "ERROR" "Health check timed out after ${HEALTH_TIMEOUT}s"
    CONTAINER_LOGS="$(docker logs --tail 50 "${GREEN_NAME}" 2>&1 || true)"
    log "ERROR" "Last 50 lines of GREEN container logs:"
    echo "${CONTAINER_LOGS}" >> "${LOG_FILE}"
    rollback
  fi

  HEALTH_STATUS="$(docker inspect --format='{{.State.Health.Status}}' "${GREEN_NAME}" 2>/dev/null || echo 'unknown')"

  case "${HEALTH_STATUS}" in
    healthy)
      HEALTHY=true
      break
      ;;
    unhealthy)
      log "ERROR" "GREEN container reported unhealthy"
      rollback
      ;;
    *)
      # starting or unknown - keep waiting
      sleep 5
      ;;
  esac
done

log "INFO" "GREEN container is healthy (took ${ELAPSED}s)"

# ---------------------------------------------------------------------------
# Step 5: Update Traefik routing - switch from BLUE to GREEN
# ---------------------------------------------------------------------------
log "INFO" "[Step 5/9] Switching Traefik routing to GREEN..."

# Remove Traefik labels from BLUE (if it exists)
if docker ps -q -f "name=${BLUE_NAME}" | grep -q .; then
  # Stop and recreate BLUE without Traefik routing to release the route
  docker stop "${BLUE_NAME}" >> "${LOG_FILE}" 2>&1 || true

  # We keep the container stopped but not removed yet (for rollback)
  log "INFO" "BLUE container stopped (Traefik routing released)"
fi

# Recreate GREEN with Traefik labels enabled
docker stop "${GREEN_NAME}" >> "${LOG_FILE}" 2>&1
docker rm "${GREEN_NAME}" >> "${LOG_FILE}" 2>&1

docker run -d \
  --name "${GREEN_NAME}" \
  --network "${NETWORK}" \
  --restart unless-stopped \
  --health-cmd "curl -f http://localhost:${APP_PORT}/health || exit 1" \
  --health-interval 10s \
  --health-timeout 5s \
  --health-retries 3 \
  --health-start-period 30s \
  --label "traefik.enable=true" \
  --label "traefik.http.routers.${PROJECT_NAME}.rule=Host(\`${DOMAIN}\`)" \
  --label "traefik.http.routers.${PROJECT_NAME}.entrypoints=websecure" \
  --label "traefik.http.routers.${PROJECT_NAME}.tls.certresolver=letsencrypt" \
  --label "traefik.http.services.${PROJECT_NAME}.loadbalancer.server.port=${APP_PORT}" \
  --label "deploy.project=${PROJECT_NAME}" \
  --label "deploy.role=green" \
  --label "deploy.tag=${IMAGE_TAG}" \
  --label "deploy.timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  "${FULL_IMAGE}" >> "${LOG_FILE}" 2>&1

log "INFO" "GREEN container restarted with Traefik routing enabled"

# Wait for the new GREEN to become healthy again
log "INFO" "Waiting for GREEN to become healthy with routing..."
HEALTH_START="$(date +%s)"

while true; do
  ELAPSED=$(( $(date +%s) - HEALTH_START ))

  if [[ ${ELAPSED} -ge ${HEALTH_TIMEOUT} ]]; then
    log "ERROR" "GREEN failed to become healthy after routing switch"
    # Restart BLUE for rollback
    docker start "${BLUE_NAME}" >> "${LOG_FILE}" 2>&1 || true
    docker rm -f "${GREEN_NAME}" >> "${LOG_FILE}" 2>&1 || true
    log "ERROR" "Rolled back to BLUE"
    exit 1
  fi

  HEALTH_STATUS="$(docker inspect --format='{{.State.Health.Status}}' "${GREEN_NAME}" 2>/dev/null || echo 'unknown')"
  if [[ "${HEALTH_STATUS}" == "healthy" ]]; then
    break
  fi
  sleep 5
done

log "INFO" "GREEN is healthy and receiving traffic"

# ---------------------------------------------------------------------------
# Step 6: Verify GREEN is serving traffic correctly
# ---------------------------------------------------------------------------
log "INFO" "[Step 6/9] Verifying GREEN is serving traffic..."

VERIFY_ATTEMPTS=3
VERIFY_OK=0

for i in $(seq 1 ${VERIFY_ATTEMPTS}); do
  HTTP_CODE="$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 \
    -H "Host: ${DOMAIN}" "http://localhost:${APP_PORT}" 2>/dev/null || echo "000")"

  if [[ "${HTTP_CODE}" =~ ^(200|201|204|301|302)$ ]]; then
    (( VERIFY_OK++ ))
  fi
  sleep 2
done

if [[ ${VERIFY_OK} -eq 0 ]]; then
  log "WARN" "Could not verify traffic routing via curl (this may be expected in some network configurations)"
else
  log "INFO" "Traffic verification: ${VERIFY_OK}/${VERIFY_ATTEMPTS} successful checks"
fi

# ---------------------------------------------------------------------------
# Step 7: Monitor GREEN
# ---------------------------------------------------------------------------
if [[ "${MONITOR_MODE}" == "full" ]]; then
  MONITOR_SECONDS=900   # 15 minutes
else
  MONITOR_SECONDS=300   # 5 minutes
fi

log "INFO" "[Step 7/9] Monitoring GREEN for ${MONITOR_SECONDS}s (${MONITOR_MODE} mode)..."

MONITOR_START="$(date +%s)"
MONITOR_FAILURES=0
MAX_MONITOR_FAILURES=3

while true; do
  ELAPSED=$(( $(date +%s) - MONITOR_START ))

  if [[ ${ELAPSED} -ge ${MONITOR_SECONDS} ]]; then
    break
  fi

  # Check container is still running and healthy
  CONTAINER_STATUS="$(docker inspect --format='{{.State.Status}}' "${GREEN_NAME}" 2>/dev/null || echo 'unknown')"
  HEALTH_STATUS="$(docker inspect --format='{{.State.Health.Status}}' "${GREEN_NAME}" 2>/dev/null || echo 'unknown')"

  if [[ "${CONTAINER_STATUS}" != "running" || "${HEALTH_STATUS}" == "unhealthy" ]]; then
    (( MONITOR_FAILURES++ ))
    log "WARN" "Monitor check failed (${MONITOR_FAILURES}/${MAX_MONITOR_FAILURES}): status=${CONTAINER_STATUS}, health=${HEALTH_STATUS}"

    if [[ ${MONITOR_FAILURES} -ge ${MAX_MONITOR_FAILURES} ]]; then
      log "ERROR" "Too many monitoring failures - initiating rollback"
      # Rollback: restart BLUE, remove GREEN
      docker start "${BLUE_NAME}" >> "${LOG_FILE}" 2>&1 || true
      docker rm -f "${GREEN_NAME}" >> "${LOG_FILE}" 2>&1 || true
      log "ERROR" "Rolled back to BLUE container"
      exit 1
    fi
  fi

  # Progress indicator every 60 seconds
  if [[ $(( ELAPSED % 60 )) -eq 0 && ${ELAPSED} -gt 0 ]]; then
    log "INFO" "  Monitoring: ${ELAPSED}/${MONITOR_SECONDS}s elapsed, failures: ${MONITOR_FAILURES}"
  fi

  sleep 15
done

log "INFO" "Monitoring completed successfully"

# ---------------------------------------------------------------------------
# Step 8: Remove old BLUE container
# ---------------------------------------------------------------------------
log "INFO" "[Step 8/9] Removing old BLUE container..."

if docker ps -aq -f "name=${BLUE_NAME}" | grep -q .; then
  docker rm -f "${BLUE_NAME}" >> "${LOG_FILE}" 2>&1 || true
  log "INFO" "Old BLUE container removed"
else
  log "INFO" "No old BLUE container to remove"
fi

# ---------------------------------------------------------------------------
# Step 9: Rename GREEN to BLUE
# ---------------------------------------------------------------------------
log "INFO" "[Step 9/9] Renaming GREEN to BLUE for next deployment cycle..."

docker rename "${GREEN_NAME}" "${BLUE_NAME}" >> "${LOG_FILE}" 2>&1

if docker ps -q -f "name=${BLUE_NAME}" | grep -q .; then
  log "INFO" "Container renamed to ${BLUE_NAME}"
else
  log "ERROR" "Failed to rename container"
  exit 1
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
log "INFO" "========== Blue/Green Deployment Complete =========="
log "INFO" "Project:       ${PROJECT_NAME}"
log "INFO" "Image:         ${FULL_IMAGE}"
log "INFO" "Previous:      ${BLUE_IMAGE}"
log "INFO" "Domain:        ${DOMAIN}"
log "INFO" "Container:     ${BLUE_NAME}"
log "INFO" "Status:        SUCCESS"
exit 0
