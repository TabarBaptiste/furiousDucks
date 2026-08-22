#!/usr/bin/env bash
###############################################################################
# prune-jenkins-workspaces.sh - Furious Ducks Jenkins Workspace Pruning
#
# Removes the workspace of branch jobs that have not been built for a while,
# while keeping the workspace of recent builds available for Jenkins'
# "Restart from Stage" after a failure.
#
# Only "workspace", "workspace@tmp" and "workspace@libs" are deleted. The job
# itself, its config and its "builds" directory (history, reports, archived
# artifacts) are never touched - they weigh a few MB, the workspace weighs
# over 1 GB per branch.
#
# Runs inside the Jenkins container (Ofelia job "prune-workspaces-daily",
# see docker-compose.backup.yml).
#
# Usage:
#   ./prune-jenkins-workspaces.sh [--dry-run]
#
# Options:
#   --dry-run      List what would be deleted without deleting anything.
#   --help, -h     Show help message
#
# Environment variables:
#   JENKINS_HOME              Jenkins home (default: /var/jenkins_home)
#   WORKSPACE_MAX_AGE_DAYS    Age threshold in days (default: 7)
###############################################################################
set -euo pipefail

JENKINS_HOME="${JENKINS_HOME:-/var/jenkins_home}"
MAX_AGE_DAYS="${WORKSPACE_MAX_AGE_DAYS:-7}"
DRY_RUN=false

for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=true ;;
        --help|-h) sed -n '2,27p' "$0"; exit 0 ;;
        *) echo "Unknown option: $arg" >&2; exit 1 ;;
    esac
done

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') [$1] $2"
}

log "INFO" "Pruning workspaces idle for more than ${MAX_AGE_DAYS} day(s) in ${JENKINS_HOME}"

freed_kb=0
pruned=0

for ws in "${JENKINS_HOME}"/jobs/*/branches/*/workspace; do
    [ -d "${ws}" ] || continue
    job_dir="$(dirname "${ws}")"
    job_name="$(basename "${job_dir}")"

    # A build in progress has no build.xml yet: never pull the workspace out
    # from under a running build. A build that crashed hard leaves the same
    # marker and keeps its workspace forever - the safe direction, and rare.
    in_progress=false
    for build in "${job_dir}"/builds/*/; do
        [ -d "${build}" ] || continue
        [ -f "${build}build.xml" ] || in_progress=true
    done
    if [ "${in_progress}" = true ]; then
        log "SKIP" "${job_name}: build in progress"
        continue
    fi

    # Keep the workspace while the job has been built recently.
    if [ -n "$(find "${job_dir}/builds" -maxdepth 1 -mindepth 1 -type d \
                    -mtime "-${MAX_AGE_DAYS}" 2>/dev/null)" ]; then
        continue
    fi

    size_kb="$(du -xsk "${ws}" 2>/dev/null | cut -f1)"
    size_kb="${size_kb:-0}"

    if [ "${DRY_RUN}" = true ]; then
        log "DRY-RUN" "${job_name}: would free $((size_kb / 1024)) MB"
    else
        rm -rf "${ws}" "${ws}@tmp" "${ws}@libs"
        log "PRUNED" "${job_name}: freed $((size_kb / 1024)) MB"
    fi

    freed_kb=$((freed_kb + size_kb))
    pruned=$((pruned + 1))
done

log "INFO" "${pruned} workspace(s), $((freed_kb / 1024)) MB total"
