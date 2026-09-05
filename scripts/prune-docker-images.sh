#!/usr/bin/env bash
###############################################################################
# prune-docker-images.sh - Furious Ducks local Docker cache pruning
#
# The VPS accumulates one application image per build (~450 MB for the front,
# ~710 MB for the API). Nothing evicts them: Harbor's retention policy governs
# the registry, not the local cache of the host that pulls from it. Measured on
# 2026-09-04: 25.8 GB of unused images and 39 GB of reclaimable build cache,
# on a 193 GB disk already 76% full.
#
# /etc/docker/daemon.json declares a BuildKit GC (reservedSpace 10 GB,
# maxUsedSpace 25 GB), but those settings are only read when the daemon
# starts. Until the next `systemctl restart docker`, this script enforces the
# same ceiling without touching the daemon.
#
# What it removes:
#   - application image tags (REGISTRY_PREFIX) that are ALL of:
#       * not referenced by any container, running or stopped - the stopped
#         "<projet>-prod-blue-previous" standbys are rollback targets and are
#         protected by this rule
#       * not a semver release tag (vX.Y.Z) - scripts/rollback.sh and the
#         "Promote Image" stage depend on those being present
#       * not ":latest" (DEV convenience tag pushed by the pipeline)
#       * older than MAX_AGE_DAYS
#   - BuildKit cache above MAX_CACHE
#
# What it never touches: tool and base images (node, cypress, trivy, semgrep,
# gitleaks, zap, k6, postgres, redis...). They are re-pulled on demand and
# removing them only slows the next build. Volumes are never touched either.
#
# Usage:
#   ./prune-docker-images.sh [--dry-run]
#
# Environment:
#   MAX_AGE_DAYS     Age threshold in days (default: 1)
#   MAX_CACHE        BuildKit cache ceiling (default: 25GB, matches daemon.json)
#   REGISTRY_PREFIX  Application image prefix (default: the Harbor registry)
#   SKIP_CACHE       Set to 1 to leave the build cache alone
###############################################################################
set -euo pipefail

MAX_AGE_DAYS="${MAX_AGE_DAYS:-1}"
MAX_CACHE="${MAX_CACHE:-25GB}"
REGISTRY_PREFIX="${REGISTRY_PREFIX:-registry.wk-archi-o24a-4-5-g1.fr/furious-ducks/}"
SKIP_CACHE="${SKIP_CACHE:-0}"
DRY_RUN=false

for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=true ;;
        --help|-h) sed -n '2,40p' "$0"; exit 0 ;;
        *) echo "Unknown option: $arg" >&2; exit 1 ;;
    esac
done

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') [$1] $2"; }

log INFO "Pruning application images older than ${MAX_AGE_DAYS} day(s) (prefix: ${REGISTRY_PREFIX})"
$DRY_RUN && log INFO "DRY RUN - nothing will be deleted"

# Images referenced by a container, running or stopped. Resolved to image IDs
# rather than tags: a container started from "repo:tag" keeps holding the ID
# even after the tag has been moved elsewhere by a newer build.
PROTECTED="$(docker ps -aq | xargs -r docker inspect --format '{{.Image}}' 2>/dev/null | sort -u)"

CUTOFF="$(date -d "-${MAX_AGE_DAYS} days" +%s)"
removed=0
kept=0

while IFS='|' read -r id repo tag; do
    [ -n "${repo:-}" ] || continue
    [ "$tag" = "<none>" ] && continue

    case "$repo" in "${REGISTRY_PREFIX}"*) ;; *) continue ;; esac

    # Release tags are rollback material: never remove them.
    case "$tag" in v[0-9]*.[0-9]*.[0-9]*|latest) kept=$((kept + 1)); continue ;; esac

    full_id="$(docker image inspect "$id" --format '{{.Id}}' 2>/dev/null || true)"
    if [ -n "$full_id" ] && grep -qxF "$full_id" <<< "$PROTECTED"; then
        kept=$((kept + 1)); continue
    fi

    created="$(docker image inspect "$id" --format '{{.Created}}' 2>/dev/null || true)"
    [ -n "$created" ] || continue
    created_ts="$(date -d "$created" +%s 2>/dev/null || echo 0)"
    [ "$created_ts" -gt "$CUTOFF" ] && { kept=$((kept + 1)); continue; }

    if $DRY_RUN; then
        log DRY-RUN "would remove ${repo}:${tag}"
    else
        # Removing the tag, not the ID: an image carrying several tags (the API
        # image is often identical across consecutive builds) must only lose
        # the tag being retired.
        if docker rmi "${repo}:${tag}" >/dev/null 2>&1; then
            log PRUNED "${repo}:${tag}"
        else
            log WARN "could not remove ${repo}:${tag} (still referenced?)"
            continue
        fi
    fi
    removed=$((removed + 1))
done < <(docker images --format '{{.ID}}|{{.Repository}}|{{.Tag}}')

log INFO "${removed} image tag(s) removed, ${kept} kept (in use, release or recent)"

# Dangling layers left behind once the last tag of an image is gone.
if ! $DRY_RUN; then
    dangling="$(docker image prune -f 2>/dev/null | tail -1 || true)"
    log INFO "Dangling layers: ${dangling:-none}"
fi

if [ "$SKIP_CACHE" != "1" ]; then
    log INFO "Trimming BuildKit cache down to ${MAX_CACHE}"
    if $DRY_RUN; then
        docker builder du 2>/dev/null | tail -3 | sed 's/^/    /'
    else
        # --keep-storage is what this Docker version accepts; newer releases
        # renamed it --max-used-space. Try both so the script survives an
        # engine upgrade.
        docker builder prune -f --keep-storage "$MAX_CACHE" >/dev/null 2>&1 \
            || docker builder prune -f --max-used-space "$MAX_CACHE" >/dev/null 2>&1 \
            || log WARN "build cache pruning failed - check the docker builder prune flags"
        docker builder du 2>/dev/null | tail -3 | sed 's/^/    /'
    fi
fi

log INFO "Done. Disk: $(df -h / | awk 'NR==2 {print $4" free ("$5" used)"}')"
