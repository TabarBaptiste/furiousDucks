#!/usr/bin/env bash
###############################################################################
# init-infrastructure.sh - Furious Ducks Infrastructure Initialization
#
# Bootstraps the entire CI/CD infrastructure: creates Docker networks,
# directories, permissions, generates .env from template, initializes
# Traefik ACME, and starts all service stacks in the correct order.
#
# Usage:
#   ./init-infrastructure.sh [--skip-start] [--force-env]
#
# Options:
#   --skip-start   Only set up prerequisites, do not start Docker stacks
#   --force-env    Overwrite existing .env with .env.example
#   --help, -h     Show help message
###############################################################################
set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${INFRA_DIR}/docker-compose.yml"
MONITORING_FILE="${INFRA_DIR}/docker-compose.monitoring.yml"
BACKUP_FILE="${INFRA_DIR}/docker-compose.backup.yml"
ENV_FILE="${INFRA_DIR}/.env"
ENV_EXAMPLE="${INFRA_DIR}/.env.example"
DOMAIN="wk-archi-o24a-4-5-g1.fr"

# Docker networks to create
NETWORKS=("ci_network" "workflow-network" "monitoring_network" "backup_network")

# Directories to create
DIRECTORIES=(
  "${INFRA_DIR}/traefik/acme"
  "${INFRA_DIR}/traefik/config"
  "${INFRA_DIR}/jenkins/data"
  "${INFRA_DIR}/grafana/provisioning/dashboards"
  "${INFRA_DIR}/grafana/provisioning/datasources"
  "${INFRA_DIR}/prometheus/data"
  "${INFRA_DIR}/alertmanager/data"
  "${INFRA_DIR}/loki/data"
  "${INFRA_DIR}/backup/data"
  "/var/log/deploy"
  "/var/log/backup"
)

# Health check timeout per stack (seconds)
HEALTH_TIMEOUT=300

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
log() {
  local level="$1"; shift
  echo "$(date '+%Y-%m-%d %H:%M:%S') [${level}] $*"
}

check_dependency() {
  if ! command -v "$1" &> /dev/null; then
    log "ERROR" "Required dependency not found: $1"
    exit 1
  fi
}

wait_for_healthy() {
  local compose_file="$1"
  local label="$2"
  local timeout="$3"
  local start elapsed

  log "INFO" "Waiting for services in ${label} to become healthy (timeout: ${timeout}s)..."
  start="$(date +%s)"

  while true; do
    elapsed=$(( $(date +%s) - start ))

    if [[ ${elapsed} -ge ${timeout} ]]; then
      log "ERROR" "Timeout waiting for ${label} services to become healthy"
      log "ERROR" "Check service logs with: docker compose -f ${compose_file} logs"
      return 1
    fi

    # Check if all containers are running
    TOTAL="$(docker compose -f "${compose_file}" ps --format json 2>/dev/null | grep -c '"running"' || echo 0)"
    EXPECTED="$(docker compose -f "${compose_file}" config --services 2>/dev/null | wc -l)"

    if [[ ${TOTAL} -ge ${EXPECTED} && ${EXPECTED} -gt 0 ]]; then
      log "INFO" "${label}: All ${TOTAL}/${EXPECTED} services are running"
      return 0
    fi

    log "INFO" "  ${label}: ${TOTAL}/${EXPECTED} services running (${elapsed}s elapsed)..."
    sleep 10
  done
}

usage() {
  cat <<USAGE
Usage: $(basename "$0") [OPTIONS]

Initialize the Furious Ducks CI/CD infrastructure.

Options:
  --skip-start   Set up prerequisites only (networks, dirs, env) without starting stacks
  --force-env    Overwrite existing .env file with values from .env.example
  --help, -h     Show this help message

This script will:
  1. Create Docker networks (ci_network, workflow-network, monitoring_network, backup_network)
  2. Create required directories with proper permissions
  3. Generate .env from .env.example (if not exists)
  4. Initialize Traefik ACME directory
  5. Start CI/CD, monitoring, and backup stacks in order
  6. Verify all services are running
  7. Print access URLs
USAGE
  exit 0
}

# ---------------------------------------------------------------------------
# Parse arguments
# ---------------------------------------------------------------------------
SKIP_START=false
FORCE_ENV=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage ;;
    --skip-start) SKIP_START=true; shift ;;
    --force-env) FORCE_ENV=true; shift ;;
    *) log "ERROR" "Unknown option: $1"; usage ;;
  esac
done

# ---------------------------------------------------------------------------
# Pre-flight checks
# ---------------------------------------------------------------------------
log "INFO" "========== Furious Ducks Infrastructure Init =========="
log "INFO" "Infrastructure directory: ${INFRA_DIR}"

check_dependency docker
check_dependency "docker compose" || check_dependency docker-compose

# Verify Docker daemon is running
if ! docker info > /dev/null 2>&1; then
  log "ERROR" "Docker daemon is not running"
  exit 1
fi

log "INFO" "Docker is available"

# ---------------------------------------------------------------------------
# Step 1: Create Docker networks
# ---------------------------------------------------------------------------
log "INFO" "[Step 1/7] Creating Docker networks..."

for network in "${NETWORKS[@]}"; do
  if docker network inspect "${network}" > /dev/null 2>&1; then
    log "INFO" "  Network '${network}' already exists"
  else
    docker network create "${network}" > /dev/null 2>&1
    log "INFO" "  Network '${network}' created"
  fi
done

# ---------------------------------------------------------------------------
# Step 2: Create required directories
# ---------------------------------------------------------------------------
log "INFO" "[Step 2/7] Creating required directories..."

for dir in "${DIRECTORIES[@]}"; do
  if [[ ! -d "${dir}" ]]; then
    mkdir -p "${dir}"
    log "INFO" "  Created: ${dir}"
  else
    log "INFO" "  Exists:  ${dir}"
  fi
done

# ---------------------------------------------------------------------------
# Step 3: Set proper permissions
# ---------------------------------------------------------------------------
log "INFO" "[Step 3/7] Setting permissions..."

# Traefik ACME file needs restrictive permissions
ACME_FILE="${INFRA_DIR}/traefik/acme/acme.json"
if [[ ! -f "${ACME_FILE}" ]]; then
  touch "${ACME_FILE}"
fi
chmod 600 "${ACME_FILE}"
log "INFO" "  acme.json permissions set to 600"

# Prometheus data directory needs to be writable by the prometheus user (UID 65534)
if [[ -d "${INFRA_DIR}/prometheus/data" ]]; then
  chmod -R 777 "${INFRA_DIR}/prometheus/data" 2>/dev/null || true
  log "INFO" "  Prometheus data directory permissions set"
fi

# Grafana data needs to be writable by grafana user (UID 472)
if [[ -d "${INFRA_DIR}/grafana/provisioning" ]]; then
  chmod -R 755 "${INFRA_DIR}/grafana/provisioning" 2>/dev/null || true
  log "INFO" "  Grafana provisioning directory permissions set"
fi

# Loki data directory
if [[ -d "${INFRA_DIR}/loki/data" ]]; then
  chmod -R 777 "${INFRA_DIR}/loki/data" 2>/dev/null || true
  log "INFO" "  Loki data directory permissions set"
fi

# AlertManager data directory
if [[ -d "${INFRA_DIR}/alertmanager/data" ]]; then
  chmod -R 777 "${INFRA_DIR}/alertmanager/data" 2>/dev/null || true
  log "INFO" "  AlertManager data directory permissions set"
fi

# Log directories
chmod -R 755 /var/log/deploy 2>/dev/null || true
chmod -R 755 /var/log/backup 2>/dev/null || true

# ---------------------------------------------------------------------------
# Step 4: Generate .env file
# ---------------------------------------------------------------------------
log "INFO" "[Step 4/7] Configuring environment..."

if [[ ! -f "${ENV_EXAMPLE}" ]]; then
  log "WARN" ".env.example not found at ${ENV_EXAMPLE}"
  log "WARN" "Skipping .env generation"
elif [[ -f "${ENV_FILE}" && "${FORCE_ENV}" == false ]]; then
  log "INFO" "  .env already exists (use --force-env to overwrite)"
else
  cp "${ENV_EXAMPLE}" "${ENV_FILE}"
  chmod 600 "${ENV_FILE}"
  log "INFO" "  .env generated from .env.example"
  log "WARN" "  IMPORTANT: Edit ${ENV_FILE} and replace all 'changeme' passwords before starting services!"
fi

# ---------------------------------------------------------------------------
# Step 5: Initialize Traefik ACME
# ---------------------------------------------------------------------------
log "INFO" "[Step 5/7] Initializing Traefik ACME..."

if [[ -f "${ACME_FILE}" ]]; then
  ACME_SIZE="$(stat -c%s "${ACME_FILE}" 2>/dev/null || echo 0)"
  if [[ ${ACME_SIZE} -eq 0 ]]; then
    log "INFO" "  ACME file is empty (will be populated by Traefik on first run)"
  else
    log "INFO" "  ACME file exists with certificates (${ACME_SIZE} bytes)"
  fi
fi

log "INFO" "  Traefik ACME directory ready: ${INFRA_DIR}/traefik/acme/"

if [[ "${SKIP_START}" == true ]]; then
  log "INFO" "========== Prerequisites Complete (--skip-start) =========="
  log "INFO" "To start services manually:"
  log "INFO" "  cd ${INFRA_DIR}"
  log "INFO" "  docker compose -f docker-compose.yml up -d"
  log "INFO" "  docker compose -f docker-compose.monitoring.yml up -d"
  log "INFO" "  docker compose -f docker-compose.backup.yml up -d"
  exit 0
fi

# ---------------------------------------------------------------------------
# Step 6: Start service stacks in order
# ---------------------------------------------------------------------------
log "INFO" "[Step 6/7] Starting service stacks..."

# Check for default passwords
if [[ -f "${ENV_FILE}" ]] && grep -q "changeme" "${ENV_FILE}" 2>/dev/null; then
  log "WARN" "==============================================="
  log "WARN" "  .env file contains default passwords!"
  log "WARN" "  Edit ${ENV_FILE} before production use."
  log "WARN" "==============================================="
fi

# --- Main CI/CD stack ---
log "INFO" "Starting main CI/CD stack..."
if [[ ! -f "${COMPOSE_FILE}" ]]; then
  log "ERROR" "Compose file not found: ${COMPOSE_FILE}"
  exit 1
fi

docker compose -f "${COMPOSE_FILE}" --env-file "${ENV_FILE}" up -d >> /dev/null 2>&1 || {
  log "ERROR" "Failed to start main CI/CD stack"
  log "ERROR" "Run: docker compose -f ${COMPOSE_FILE} up -d  for details"
  exit 1
}
log "INFO" "Main CI/CD stack started"

# Wait for main services to be healthy
wait_for_healthy "${COMPOSE_FILE}" "CI/CD" "${HEALTH_TIMEOUT}" || {
  log "WARN" "Some CI/CD services may not be fully healthy yet. Continuing..."
}

# --- Monitoring stack ---
if [[ -f "${MONITORING_FILE}" ]]; then
  log "INFO" "Starting monitoring stack..."
  docker compose -f "${MONITORING_FILE}" --env-file "${ENV_FILE}" up -d >> /dev/null 2>&1 || {
    log "ERROR" "Failed to start monitoring stack"
    log "WARN" "Main CI/CD stack is running. Fix monitoring and retry."
  }
  log "INFO" "Monitoring stack started"

  wait_for_healthy "${MONITORING_FILE}" "Monitoring" "${HEALTH_TIMEOUT}" || {
    log "WARN" "Some monitoring services may not be fully healthy yet"
  }
else
  log "WARN" "Monitoring compose file not found: ${MONITORING_FILE}"
fi

# --- Backup stack ---
if [[ -f "${BACKUP_FILE}" ]]; then
  log "INFO" "Starting backup stack..."
  docker compose -f "${BACKUP_FILE}" --env-file "${ENV_FILE}" up -d >> /dev/null 2>&1 || {
    log "ERROR" "Failed to start backup stack"
    log "WARN" "Main stacks are running. Fix backup and retry."
  }
  log "INFO" "Backup stack started"
else
  log "WARN" "Backup compose file not found: ${BACKUP_FILE}"
fi

# ---------------------------------------------------------------------------
# Step 7: Verify all services and print URLs
# ---------------------------------------------------------------------------
log "INFO" "[Step 7/7] Verifying services and printing access URLs..."

echo ""
echo "================================================================="
echo "  Furious Ducks CI/CD Infrastructure"
echo "================================================================="
echo ""

# List all running containers in the infrastructure
RUNNING_CONTAINERS="$(docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}' 2>/dev/null || true)"
echo "${RUNNING_CONTAINERS}"
echo ""

echo "================================================================="
echo "  Access URLs"
echo "================================================================="
echo ""
echo "  GitLab:        https://git.${DOMAIN}"
echo "  Jenkins:       https://ci.${DOMAIN}"
echo "  SonarQube:     https://sonar.${DOMAIN}"
echo "  Harbor:        https://registry.${DOMAIN}"
echo "  Grafana:       https://monitor.${DOMAIN}"
echo "  Prometheus:    https://prometheus.${DOMAIN}"
echo "  MinIO Console: https://minio.${DOMAIN}"
echo "  Traefik:       https://traefik.${DOMAIN}"
echo ""
echo "================================================================="
echo "  Notes"
echo "================================================================="
echo ""
echo "  - Default credentials are in ${ENV_FILE}"
echo "  - Change ALL default passwords before production use"
echo "  - View logs: docker compose -f <file> logs -f <service>"
echo "  - GitLab may take 5-10 minutes to fully initialize"
echo ""
echo "================================================================="

log "INFO" "========== Infrastructure Initialization Complete =========="
exit 0
