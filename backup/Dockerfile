FROM restic/restic:0.17.0

# coreutils provides GNU date (busybox date cannot parse restic RFC3339 timestamps)
RUN apk add --no-cache bash curl coreutils postgresql16-client

COPY scripts/ /scripts/
RUN chmod +x /scripts/*.sh

# Keep container running
ENTRYPOINT ["tail", "-f", "/dev/null"]
