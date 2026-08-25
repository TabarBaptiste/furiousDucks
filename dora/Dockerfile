FROM python:3.13-alpine

RUN pip install --no-cache-dir requests==2.32.3

COPY scripts/ /scripts/
RUN chmod +x /scripts/*.py

# Ofelia triggers the script via `docker exec`; keep the container alive.
ENTRYPOINT ["tail", "-f", "/dev/null"]
