#!/bin/bash
set -euo pipefail

# The GID owning /var/run/docker.sock varies by host (depends on how Docker
# was installed there), so it can't be baked into the image at build time
# without silently drifting out of sync. Align it here at container start
# instead, then drop back to the jenkins user to run the real entrypoint.
DOCKER_SOCK=/var/run/docker.sock

if [ -S "$DOCKER_SOCK" ]; then
    SOCK_GID="$(stat -c '%g' "$DOCKER_SOCK")"
    if getent group "$SOCK_GID" >/dev/null 2>&1; then
        GROUP_NAME="$(getent group "$SOCK_GID" | cut -d: -f1)"
    else
        GROUP_NAME=docker-host
        groupadd -g "$SOCK_GID" "$GROUP_NAME"
    fi
    usermod -aG "$GROUP_NAME" jenkins
fi

exec su -s /bin/bash jenkins -c 'exec /usr/bin/tini -- /usr/local/bin/jenkins.sh "$@"' -- jenkins "$@"
