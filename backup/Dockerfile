FROM restic/restic:0.17.0

RUN apk add --no-cache bash curl postgresql16-client

COPY scripts/ /scripts/
RUN chmod +x /scripts/*.sh

# Keep container running
ENTRYPOINT ["tail", "-f", "/dev/null"]
