###############################################################################
# Jenkins Custom Dockerfile - Furious Ducks CI/CD
#
# Builds a pre-configured Jenkins instance with:
#   - JCasC (Configuration as Code)
#   - All required plugins pre-installed
#   - Docker CLI for Docker-in-Docker builds
#   - Tuned JVM settings
###############################################################################

FROM jenkins/jenkins:2.568.2-lts-jdk21

# ---------------------------------------------------------------------------
# Environment
# ---------------------------------------------------------------------------
# Skip the initial setup wizard (JCasC handles everything)
# JVM: 512 MB initial heap, 2 GB max
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false -Xmx2g -Xms512m -XX:+UseG1GC -XX:MaxGCPauseMillis=30"

# Path where JCasC looks for configuration YAML files
ENV CASC_JENKINS_CONFIG=/var/jenkins_home/casc

# ---------------------------------------------------------------------------
# Plugins
# ---------------------------------------------------------------------------
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

# ---------------------------------------------------------------------------
# JCasC configuration
# ---------------------------------------------------------------------------
COPY casc/ /var/jenkins_home/casc/

# ---------------------------------------------------------------------------
# Docker CLI (for Docker-in-Docker builds)
# ---------------------------------------------------------------------------
USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       ca-certificates \
       curl \
       gnupg \
    && install -m 0755 -d /etc/apt/keyrings \
    && curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc \
    && chmod a+r /etc/apt/keyrings/docker.asc \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $(. /etc/os-release && echo "$VERSION_CODENAME") stable" > /etc/apt/sources.list.d/docker.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends docker-ce-cli docker-buildx-plugin \
    && rm -rf /var/lib/apt/lists/*

# ---------------------------------------------------------------------------
# Entrypoint
# ---------------------------------------------------------------------------
# Aligns the container's docker group with the host's actual docker.sock GID
# at startup (it varies per host), then drops privileges to run Jenkins as
# the jenkins user. Stays as root here so the entrypoint can do that.
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
