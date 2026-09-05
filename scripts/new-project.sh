#!/usr/bin/env bash
###############################################################################
# new-project.sh - Furious Ducks Project Onboarding
#
# Automates the creation and configuration of a new project in the Furious
# Ducks CI/CD workflow:
#   1. Creates the GitLab project via API
#   2. Generates and pushes the Jenkinsfile (shared library)
#   3. Initialises GitFlow branches (main + develop)
#   4. Protects main and develop branches
#   5. Configures the GitLab → Jenkins webhook
#   6. Creates the Jenkins Multibranch Pipeline job
#
# Usage:
#   ./new-project.sh [--non-interactive]
#
# Options:
#   --non-interactive   Read all values from environment variables (CI mode)
#   --help, -h          Show this help message
#
# Environment variables (required in non-interactive mode):
#   PROJECT_NAME        Project slug (e.g. thetiptop)
#   PROJECT_STACK       Stack: node | php | python | ruby
#   APP_PORT            Container port exposed by the app (e.g. 3000)
#   RUN_E2E             true | false
#   RUN_K6              true | false
#   GITLAB_URL          GitLab base URL (e.g. https://git.wk-archi-o24a-4-5-g1.fr)
#   GITLAB_TOKEN        GitLab personal access token (api scope)
#   GITLAB_USER         GitLab namespace / username (e.g. root)
#   JENKINS_URL         Jenkins base URL (e.g. https://ci.wk-archi-o24a-4-5-g1.fr)
#   JENKINS_USER        Jenkins admin username
#   JENKINS_TOKEN       Jenkins API token
#   DOMAIN_WK           Workflow domain  (e.g. wk-archi-o24a-4-5-g1.fr)
#   DOMAIN_DSP5         App domain       (e.g. dsp5-archi-o24a-4-5-g1.fr)
#   PROD_DOMAIN         Optional. Prod domain (default: <project>.$DOMAIN_DSP5)
#   GIT_SSH_PORT        SSH port for GitLab (default: 2222)
###############################################################################
set -euo pipefail

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GIT_SSH_PORT="${GIT_SSH_PORT:-2222}"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
log()   { echo "$(date '+%Y-%m-%d %H:%M:%S') [${1}] ${*:2}"; }
ok()    { echo "  ✅  $*"; }
warn()  { echo "  ⚠️   $*"; }
err()   { echo "  ❌  $*" >&2; exit 1; }
sep()   { echo ""; echo "──────────────────────────────────────────────"; echo "  $*"; echo "──────────────────────────────────────────────"; }

require_cmd() { command -v "$1" &>/dev/null || err "Required command not found: $1"; }

usage() {
  grep '^#' "$0" | sed 's/^# \{0,1\}//' | head -40
  exit 0
}

# ---------------------------------------------------------------------------
# Parse arguments
# ---------------------------------------------------------------------------
NON_INTERACTIVE=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h)            usage ;;
    --non-interactive)    NON_INTERACTIVE=true; shift ;;
    *)                    err "Unknown option: $1" ;;
  esac
done

# ---------------------------------------------------------------------------
# Dependency checks
# ---------------------------------------------------------------------------
require_cmd curl
require_cmd git
require_cmd jq

# ---------------------------------------------------------------------------
# Interactive prompts (skipped in non-interactive mode)
# ---------------------------------------------------------------------------
ask() {
  local var="$1" prompt="$2" default="${3:-}"
  if [[ "${NON_INTERACTIVE}" == true ]]; then
    [[ -n "${!var:-}" ]] || err "Variable ${var} is required in non-interactive mode"
    return
  fi
  local value
  if [[ -n "${default}" ]]; then
    read -rp "  ${prompt} [${default}]: " value
    value="${value:-${default}}"
  else
    read -rp "  ${prompt}: " value
    [[ -n "${value}" ]] || err "${var} cannot be empty"
  fi
  printf -v "${var}" '%s' "${value}"
}

ask_bool() {
  local var="$1" prompt="$2" default="${3:-true}"
  if [[ "${NON_INTERACTIVE}" == true ]]; then
    [[ -n "${!var:-}" ]] || printf -v "${var}" '%s' "${default}"
    return
  fi
  local value
  read -rp "  ${prompt} (true/false) [${default}]: " value
  value="${value:-${default}}"
  printf -v "${var}" '%s' "${value}"
}

sep "Furious Ducks – New Project Onboarding"

echo ""
echo "  This script will create and configure a new project in the"
echo "  Furious Ducks CI/CD workflow."
echo ""

# ---------------------------------------------------------------------------
# Collect configuration
# ---------------------------------------------------------------------------
sep "1/6  Project configuration"

ask PROJECT_NAME  "Project slug (lowercase, no spaces)"
ask PROJECT_STACK "Stack (node / php / python / ruby)" "node"
ask APP_PORT      "Container port exposed by the app"   "3000"
ask_bool RUN_E2E  "Enable E2E tests (Cypress/Playwright)" "true"
ask_bool RUN_K6   "Generate the scheduled K6 load-test job" "true"

sep "2/6  Infrastructure URLs"

ask GITLAB_URL   "GitLab base URL"   "https://git.wk-archi-o24a-4-5-g1.fr"
ask GITLAB_TOKEN "GitLab personal access token (api scope)"
ask GITLAB_USER  "GitLab namespace / username" "root"
ask JENKINS_URL  "Jenkins base URL"  "https://ci.wk-archi-o24a-4-5-g1.fr"
ask JENKINS_USER "Jenkins admin username" "admin"
ask JENKINS_TOKEN "Jenkins API token"
ask DOMAIN_WK    "Workflow domain"   "wk-archi-o24a-4-5-g1.fr"
ask DOMAIN_DSP5  "App (client) domain" "dsp5-archi-o24a-4-5-g1.fr"
if [[ -z "${PROD_DOMAIN:-}" ]]; then
  if [[ "${NON_INTERACTIVE}" == true ]]; then
    PROD_DOMAIN="${PROJECT_NAME}.${DOMAIN_DSP5}"
  else
    ask PROD_DOMAIN "Prod domain" "${PROJECT_NAME}.${DOMAIN_DSP5}"
  fi
fi

# Derived values
GITLAB_HOST="${GITLAB_URL#https://}"
GITLAB_HOST="${GITLAB_HOST#http://}"
REPO_SSH="ssh://git@${GITLAB_HOST}:${GIT_SSH_PORT}/${GITLAB_USER}/${PROJECT_NAME}.git"
REPO_HTTP="${GITLAB_URL}/${GITLAB_USER}/${PROJECT_NAME}.git"
JENKINS_WEBHOOK_URL="${JENKINS_URL}/project/${PROJECT_NAME}"

DEV_DOMAIN="${PROJECT_NAME}-dev.${DOMAIN_WK}"
PREPROD_DOMAIN="${PROJECT_NAME}-preprod.${DOMAIN_WK}"

echo ""
log "INFO" "Project        : ${PROJECT_NAME}"
log "INFO" "Stack          : ${PROJECT_STACK}"
log "INFO" "Port           : ${APP_PORT}"
log "INFO" "E2E            : ${RUN_E2E}  |  K6: ${RUN_K6}"
log "INFO" "GitLab         : ${GITLAB_URL}/${GITLAB_USER}/${PROJECT_NAME}"
log "INFO" "Jenkins        : ${JENKINS_URL}"
log "INFO" "Dev domain     : https://${DEV_DOMAIN}"
log "INFO" "Preprod domain : https://${PREPROD_DOMAIN}"
log "INFO" "Prod domain    : https://${PROD_DOMAIN}"

if [[ "${NON_INTERACTIVE}" == false ]]; then
  echo ""
  read -rp "  ▶  Proceed? (y/N): " confirm
  [[ "${confirm,,}" == "y" ]] || { log "INFO" "Aborted."; exit 0; }
fi

# ---------------------------------------------------------------------------
# Step 1: Create GitLab project
# ---------------------------------------------------------------------------
sep "Step 1/6 – Creating GitLab project"

HTTP_STATUS="$(curl -sf -o /dev/null -w "%{http_code}" \
  --header "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_USER}%2F${PROJECT_NAME}" 2>/dev/null || echo "000")"

if [[ "${HTTP_STATUS}" == "200" ]]; then
  warn "GitLab project '${PROJECT_NAME}' already exists - skipping creation"
else
  RESPONSE="$(curl -sf -X POST \
    --header "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
    --header "Content-Type: application/json" \
    --data "{
      \"name\": \"${PROJECT_NAME}\",
      \"path\": \"${PROJECT_NAME}\",
      \"namespace_id\": null,
      \"visibility\": \"private\",
      \"initialize_with_readme\": false,
      \"default_branch\": \"main\"
    }" \
    "${GITLAB_URL}/api/v4/projects" 2>/dev/null)" || err "Failed to create GitLab project"

  PROJECT_ID="$(echo "${RESPONSE}" | jq -r '.id')"
  [[ "${PROJECT_ID}" != "null" && -n "${PROJECT_ID}" ]] || err "GitLab returned unexpected response: ${RESPONSE}"
  ok "GitLab project created (id: ${PROJECT_ID})"
fi

# Fetch project ID if it already existed
if [[ -z "${PROJECT_ID:-}" ]]; then
  PROJECT_ID="$(curl -sf \
    --header "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
    "${GITLAB_URL}/api/v4/projects/${GITLAB_USER}%2F${PROJECT_NAME}" \
    | jq -r '.id')"
fi

# ---------------------------------------------------------------------------
# Step 2: Generate Jenkinsfile and initialise Git repo
# ---------------------------------------------------------------------------
sep "Step 2/6 – Generating Jenkinsfile and initialising GitFlow"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

cd "${WORK_DIR}"
git init -b main

# Generate Jenkinsfile
cat > Jenkinsfile << JENKINSFILE
@Library('furious-ducks-shared-library') _

ciPipeline(
    projectName   : '${PROJECT_NAME}',
    stack         : '${PROJECT_STACK}',
    sonarProject  : '${PROJECT_NAME}',

    deployDev     : true,
    deployPreprod : true,
    deployProd    : true,

    runE2E        : ${RUN_E2E},

    devDomain     : '${DEV_DOMAIN}',
    preprodDomain : '${PREPROD_DOMAIN}',
    prodDomain    : '${PROD_DOMAIN}',

    appPort       : ${APP_PORT}
)
JENKINSFILE

# Generate Jenkinsfile.k6 - load testing is a separate, scheduled job, not a
# stage of the build pipeline. It needs its own Jenkins Pipeline job (see the
# generated README).
COMMIT_FILES="Jenkinsfile README.md"
if [[ "${RUN_K6}" == "true" ]]; then
  cat > Jenkinsfile.k6 << K6FILE
@Library('furious-ducks-shared-library') _

k6Pipeline(
    projectName: '${PROJECT_NAME}',
    targetUrl  : 'https://${PREPROD_DOMAIN}',
    k6Script   : 'tests/performance/load-test.js',
    k6Vus      : 50,
    k6Duration : '3m',
    cron       : 'H 2 * * 1'
)
K6FILE
  COMMIT_FILES="${COMMIT_FILES} Jenkinsfile.k6"
fi

# Generate README
cat > README.md << README
# ${PROJECT_NAME}

Projet géré via le workflow CI/CD Furious Ducks.

| Environnement | URL |
|---|---|
| DEV | https://${DEV_DOMAIN} |
| PREPROD | https://${PREPROD_DOMAIN} |
| PROD | https://${PROD_DOMAIN} |

## Branches

| Branche | Rôle |
|---|---|
| \`main\` | Production |
| \`develop\` | Intégration continue |
| \`feature/*\` | Fonctionnalités |
| \`release/*\` | Préparation de release |
| \`hotfix/*\` | Correctifs urgents |

## Pipeline

Le pipeline est défini dans \`Jenkinsfile\` via la shared library \`furious-ducks-shared-library\`.
Pour modifier le comportement du pipeline, modifiez uniquement le \`Jenkinsfile\` - aucun accès Jenkins requis.

Stack : \`${PROJECT_STACK}\` | Port applicatif : \`${APP_PORT}\`

## Tests de charge

Les tests de charge k6 ne font pas partie du pipeline de build : ils tournent
dans un job Jenkins dédié, planifié, défini par \`Jenkinsfile.k6\`.

À créer une fois dans Jenkins : job de type *Pipeline* (pas multibranch) sur ce
dépôt, branche \`main\`, script path \`Jenkinsfile.k6\`. Lancez-le une première
fois manuellement pour que Jenkins enregistre le cron et les paramètres.
README

git config user.email "devops@${DOMAIN_WK}"
git config user.name  "Furious Ducks DevOps"

git add ${COMMIT_FILES}
git commit -m "ci: initialise project with Furious Ducks CI/CD workflow

- Add Jenkinsfile using furious-ducks-shared-library
- Stack: ${PROJECT_STACK}, port: ${APP_PORT}
- Environments: DEV / PREPROD / PROD
- E2E: ${RUN_E2E}, K6 (scheduled job): ${RUN_K6}"

# Push main
git remote add origin "${REPO_SSH}"
git push -u origin main
ok "main branch pushed"

# Create and push develop
git checkout -b develop
git push -u origin develop
ok "develop branch pushed"

# ---------------------------------------------------------------------------
# Step 3: Protect branches
# ---------------------------------------------------------------------------
sep "Step 3/6 – Protecting branches (main + develop)"

for branch in main develop; do
  # Remove existing protection if any
  curl -sf -X DELETE \
    --header "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
    "${GITLAB_URL}/api/v4/projects/${PROJECT_ID}/protected_branches/${branch}" \
    > /dev/null 2>&1 || true

  # 40 = Maintainer, 30 = Developer
  PUSH_LEVEL=40   # Maintainers only
  MERGE_LEVEL=30  # Developers can merge (via MR)

  curl -sf -X POST \
    --header "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
    --header "Content-Type: application/json" \
    --data "{
      \"name\": \"${branch}\",
      \"push_access_level\": ${PUSH_LEVEL},
      \"merge_access_level\": ${MERGE_LEVEL},
      \"allow_force_push\": false
    }" \
    "${GITLAB_URL}/api/v4/projects/${PROJECT_ID}/protected_branches" \
    > /dev/null || warn "Could not protect branch '${branch}'"

  ok "Branch '${branch}' protected (push: Maintainers, merge: Developers)"
done

# ---------------------------------------------------------------------------
# Step 4: Configure GitLab webhook → Jenkins
# ---------------------------------------------------------------------------
sep "Step 4/6 – Configuring GitLab webhook → Jenkins"

WEBHOOK_RESPONSE="$(curl -sf -X POST \
  --header "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
  --header "Content-Type: application/json" \
  --data "{
    \"url\": \"${JENKINS_WEBHOOK_URL}\",
    \"push_events\": true,
    \"merge_requests_events\": true,
    \"tag_push_events\": true,
    \"enable_ssl_verification\": true
  }" \
  "${GITLAB_URL}/api/v4/projects/${PROJECT_ID}/hooks" 2>/dev/null)" || {
    warn "Could not create webhook - configure it manually in GitLab"
    WEBHOOK_RESPONSE="{}"
  }

WEBHOOK_ID="$(echo "${WEBHOOK_RESPONSE}" | jq -r '.id // "?"')"
ok "Webhook created (id: ${WEBHOOK_ID}) → ${JENKINS_WEBHOOK_URL}"

# ---------------------------------------------------------------------------
# Step 5: Create Jenkins Multibranch Pipeline job
# ---------------------------------------------------------------------------
sep "Step 5/6 – Creating Jenkins Multibranch Pipeline"

JENKINS_JOB_XML="<?xml version='1.1' encoding='UTF-8'?>
<org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject plugin=\"workflow-multibranch\">
  <actions/>
  <description>Pipeline CI/CD - ${PROJECT_NAME} (stack: ${PROJECT_STACK})</description>
  <sources class=\"jenkins.branch.MultiBranchProject\$BranchSourceList\">
    <data>
      <jenkins.branch.BranchSource>
        <source class=\"io.jenkins.plugins.gitlabbranchsource.GitLabSCMSource\">
          <id>${PROJECT_NAME}-gitlab-source</id>
          <serverName>GitLab</serverName>
          <credentialsId>gitlab-api-token</credentialsId>
          <projectOwner>${GITLAB_USER}</projectOwner>
          <projectPath>${GITLAB_USER}/${PROJECT_NAME}</projectPath>
          <traits>
            <jenkins.plugins.git.traits.SSHCheckoutTrait>
              <credentialsId>gitlab-ssh-key-local</credentialsId>
            </jenkins.plugins.git.traits.SSHCheckoutTrait>
          </traits>
        </source>
      </jenkins.branch.BranchSource>
    </data>
  </sources>
  <factory class=\"org.jenkinsci.plugins.workflow.multibranch.WorkflowBranchProjectFactory\">
    <owner class=\"org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject\" reference=\"../../..\"/>
    <scriptPath>Jenkinsfile</scriptPath>
  </factory>
</org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject>"

HTTP_CODE="$(curl -sf -o /dev/null -w "%{http_code}" \
  -X POST \
  --user "${JENKINS_USER}:${JENKINS_TOKEN}" \
  --header "Content-Type: application/xml" \
  --data "${JENKINS_JOB_XML}" \
  "${JENKINS_URL}/createItem?name=${PROJECT_NAME}" 2>/dev/null || echo "000")"

case "${HTTP_CODE}" in
  200|201) ok "Jenkins Multibranch Pipeline '${PROJECT_NAME}' created" ;;
  400)     warn "Jenkins job '${PROJECT_NAME}' already exists - skipping creation" ;;
  *)       warn "Jenkins returned HTTP ${HTTP_CODE} - create the job manually (see Step 5.4 in 00-getting-started.md)" ;;
esac

# ---------------------------------------------------------------------------
# Step 6: Trigger initial branch scan in Jenkins
# ---------------------------------------------------------------------------
sep "Step 6/6 – Triggering Jenkins branch scan"

HTTP_CODE="$(curl -sf -o /dev/null -w "%{http_code}" \
  -X POST \
  --user "${JENKINS_USER}:${JENKINS_TOKEN}" \
  "${JENKINS_URL}/job/${PROJECT_NAME}/build" 2>/dev/null || echo "000")"

if [[ "${HTTP_CODE}" =~ ^2 ]]; then
  ok "Branch scan triggered"
else
  warn "Could not trigger scan (HTTP ${HTTP_CODE}) - trigger it manually in Jenkins > ${PROJECT_NAME} > Scan"
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
sep "✅  Onboarding complete"

echo ""
echo "  Project   : ${PROJECT_NAME}"
echo "  GitLab    : ${GITLAB_URL}/${GITLAB_USER}/${PROJECT_NAME}"
echo "  Jenkins   : ${JENKINS_URL}/job/${PROJECT_NAME}"
echo ""
echo "  Environments:"
echo "    DEV     → https://${DEV_DOMAIN}"
echo "    PREPROD → https://${PREPROD_DOMAIN}"
echo "    PROD    → https://${PROD_DOMAIN}"
echo ""
echo "  Next steps:"
echo "    1. Go to Jenkins > ${PROJECT_NAME} and wait for the branch scan to finish"
echo "    2. Trigger a first build on 'develop'"
echo "    3. Check the pipeline stages in Console Output"
echo ""