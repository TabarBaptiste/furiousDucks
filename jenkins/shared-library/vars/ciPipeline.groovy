#!/usr/bin/env groovy
// =============================================================================
// Furious Ducks CI/CD - Shared Library Main Pipeline
//
// Usage (in a Jenkinsfile):
//   @Library('furious-ducks-shared-library') _
//   ciPipeline(
//       projectName : 'my-app',
//       stack       : 'node',   // 'node' | 'php' | 'python' | 'ruby'
//       sonarProject: 'my-app',
//       deployDev   : true,
//       deployPreprod: true,
//       deployProd  : true,
//       runE2E      : true,
//       runK6       : true,
//       devDomain   : 'my-app-dev.wk-archi-o24a-4-5-g1.fr',
//       preprodDomain: 'my-app-preprod.wk-archi-o24a-4-5-g1.fr',
//       prodDomain  : 'my-app.dsp5-archi-o24a-4-5-g1.fr',
//       appPort     : 3000,
//       k6Vus       : 50,
//       k6Duration  : '3m'
//   )
// =============================================================================

@SuppressWarnings(['MethodSize', 'DuplicateStringLiteral', 'DuplicateNumberLiteral'])
void call(Map config = [:]) {
    // -------------------------------------------------------------------------
    // Default configuration
    // -------------------------------------------------------------------------
    String projectName    = config.projectName    ?: env.JOB_NAME
    String registry       = config.registry       ?: 'registry.wk-archi-o24a-4-5-g1.fr'
    String harborProject  = config.harborProject  ?: 'furious-ducks'
    String imageName      = "${registry}/${harborProject}/${projectName}"
    String sonarProject   = config.sonarProject   ?: projectName
    String stack          = config.stack           ?: 'node'

    // Deployment flags
    boolean deployDev      = config.deployDev      != false
    boolean deployPreprod  = config.deployPreprod   != false
    boolean deployProd     = config.deployProd      ?: false

    // Test flags
    boolean runE2E         = config.runE2E         ?: false
    boolean runK6          = config.runK6          ?: false

    // Domains
    // DEV/PREPROD are internal workflow environments -> wk-* (platform domain).
    // PROD is the delivered client site -> dsp5-* (site internet domain), per
    // the school's domain naming requirements (informations_mise_en_ligne.md).
    String devDomain      = config.devDomain      ?: "${projectName}-dev.wk-archi-o24a-4-5-g1.fr"
    String preprodDomain  = config.preprodDomain  ?: "${projectName}-preprod.wk-archi-o24a-4-5-g1.fr"
    String prodDomain     = config.prodDomain     ?: "${projectName}.dsp5-archi-o24a-4-5-g1.fr"

    // Networking
    int appPort        = config.appPort        ?: 3000

    String dockerfilePath = config.dockerfile ?: 'Dockerfile'

    // K6 Performance settings
    int k6Vus          = config.k6Vus          ?: 50
    String k6Duration  = config.k6Duration     ?: '3m'
    String k6Script    = config.k6Script       ?: 'tests/performance/load-test.js'

    // E2E settings
    String e2eFramework   = config.e2eFramework   ?: 'playwright'  // 'playwright' or 'cypress'

    // Docker network for Traefik
    String traefikNetwork = config.traefikNetwork ?: 'workflow-network'

    // -------------------------------------------------------------------------
    // Pipeline Definition
    // -------------------------------------------------------------------------
    pipeline {
        agent any

        options {
            timestamps()
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '20'))
            timeout(time: 60, unit: 'MINUTES')
            ansiColor('xterm')
        }

        environment {
            HARBOR_CREDENTIALS = credentials('harbor-credentials')
            SONAR_TOKEN        = credentials('sonarqube-token')
            DOCKER_IMAGE       = "${imageName}"
        }

        stages {
            // =================================================================
            // STAGE: Checkout
            // =================================================================
            stage('Checkout') {
                steps {
                    checkout scm
                    script {
                        env.GIT_COMMIT_SHORT = sh(
                            script: 'git rev-parse --short HEAD',
                            returnStdout: true
                        ).trim()
                        env.GIT_COMMIT_MSG = sh(
                            script: 'git log -1 --pretty=%s',
                            returnStdout: true
                        ).trim()
                        String gitTag = sh(
                            script: 'git describe --tags --exact-match 2>/dev/null || true',
                            returnStdout: true
                        ).trim()
                        if (gitTag == 'null') { gitTag = '' }
                        String safeBranch = env.BRANCH_NAME.replaceAll('[^a-zA-Z0-9._-]', '-')
                        env.IMAGE_TAG = gitTag ?: "${safeBranch}-${env.GIT_COMMIT_SHORT}"
                        echo "Image tag: ${env.IMAGE_TAG}"

                        // Translate Jenkins container path → host path for Docker volume mounts
                        String jenkinsHomeHost = env.JENKINS_HOME_HOST ?:
                            '/var/lib/docker/volumes/common-jenkins-data-ci/_data'
                        env.HOST_WORKSPACE = env.WORKSPACE.replace('/var/jenkins_home', jenkinsHomeHost)
                        echo "Host workspace: ${env.HOST_WORKSPACE}"
                    }
                }
            }

            // =================================================================
            // STAGE: Install Dependencies
            // =================================================================
            stage('Install Dependencies') {
                steps {
                    installDependencies(stack, env.HOST_WORKSPACE)
                }
            }

            // =================================================================
            // STAGE: Lint
            // =================================================================
            stage('Lint') {
                steps {
                    runLint(stack, env.HOST_WORKSPACE)
                }
            }

            // =================================================================
            // STAGE: Unit Tests
            // =================================================================
            stage('Unit Tests') {
                steps {
                    runUnitTests(stack, env.HOST_WORKSPACE)
                }
                post {
                    always {
                        publishJUnitReports()
                    }
                }
            }

            // =================================================================
            // STAGE: SonarQube Analysis
            // =================================================================
            stage('SonarQube Analysis') {
                steps {
                    withSonarQubeEnv('SonarQube') {
                        runSonarAnalysis(stack, sonarProject)
                    }
                }
            }

            // =================================================================
            // STAGE: Quality Gate
            // =================================================================
            stage('Quality Gate') {
                steps {
                    waitForQualityGateResult()
                }
            }

            // =================================================================
            // STAGE: Build Assets (front-end stacks only)
            // =================================================================
            stage('Build Assets') {
                when {
                    expression { return stack == 'node' }
                }
                steps {
                    runBuildAssets(env.HOST_WORKSPACE)
                }
            }

            // =================================================================
            // STAGE: Docker Build
            // =================================================================
            stage('Docker Build') {
                when {
                    anyOf {
                        branch 'main'
                        branch 'develop'
                        expression { return env.TAG_NAME ==~ /v\d+\.\d+\.\d+.*/ }
                    }
                }
                steps {
                    echo "Building ${env.DOCKER_IMAGE}:${env.IMAGE_TAG}"
                    sh """
                        docker build \
                            --provenance=false \
                            --sbom=false \
                            --build-arg BUILD_DATE=\$(date -u +"%Y-%m-%dT%H:%M:%SZ") \
                            --build-arg VCS_REF=${env.GIT_COMMIT_SHORT} \
                            --build-arg VERSION=${env.IMAGE_TAG} \
                            -t ${env.DOCKER_IMAGE}:${env.IMAGE_TAG} \
                            -f ${dockerfilePath} \
                            .
                    """
                    script {
                        if (env.BRANCH_NAME == 'develop') {
                            sh "docker tag ${env.DOCKER_IMAGE}:${env.IMAGE_TAG} ${env.DOCKER_IMAGE}:latest"
                        }
                    }
                }
            }

            // =================================================================
            // STAGE: Trivy Security Scan
            // Runs BEFORE the push so a vulnerable image never reaches Harbor.
            // =================================================================
            stage('Trivy Scan') {
                when {
                    anyOf {
                        branch 'main'
                        branch 'develop'
                        expression { return env.TAG_NAME ==~ /v\d+\.\d+\.\d+.*/ }
                    }
                }
                steps {
                    trivyScan(env.DOCKER_IMAGE, env.IMAGE_TAG, env.HOST_WORKSPACE)
                }
            }

            // =================================================================
            // STAGE: Docker Push (to Harbor registry)
            // =================================================================
            stage('Docker Push') {
                when {
                    anyOf {
                        branch 'main'
                        branch 'develop'
                        expression { return env.TAG_NAME ==~ /v\d+\.\d+\.\d+.*/ }
                    }
                }
                steps {
                    pushDockerImage(registry, env.DOCKER_IMAGE, env.IMAGE_TAG, env.BRANCH_NAME == 'develop')
                }
            }

            // =================================================================
            // STAGE: Gitleaks (Secret Detection)
            // =================================================================
            stage('Gitleaks') {
                when {
                    anyOf { branch 'main'; branch 'develop' }
                }
                steps {
                    runGitleaks(env.HOST_WORKSPACE)
                }
            }

            // =================================================================
            // STAGE: Deploy DEV
            // =================================================================
            stage('Deploy DEV') {
                when {
                    expression { return env.BRANCH_NAME == 'develop' && deployDev }
                }
                steps {
                    deployContainer(
                        projectName   : projectName,
                        environment   : 'dev',
                        imageTag      : env.IMAGE_TAG,
                        domain        : devDomain,
                        port          : appPort,
                        registry      : registry,
                        harborProject : harborProject,
                        traefikNetwork: traefikNetwork
                    )
                }
            }

            // =================================================================
            // STAGE: Semgrep SAST (Static Application Security Testing)
            // =================================================================
            stage('Semgrep SAST') {
                when {
                    branch 'main'
                }
                steps {
                    runSemgrep(env.HOST_WORKSPACE)
                }
            }

            // =================================================================
            // STAGE: Deploy PREPROD
            // Deployed before E2E/K6 so those suites validate the freshly
            // promoted PREPROD build (iso-prod), per the CST deployment process.
            // =================================================================
            stage('Deploy PREPROD') {
                when {
                    expression { return env.BRANCH_NAME == 'main' && deployPreprod }
                }
                steps {
                    deployContainer(
                        projectName   : projectName,
                        environment   : 'preprod',
                        imageTag      : env.IMAGE_TAG,
                        domain        : preprodDomain,
                        port          : appPort,
                        registry      : registry,
                        harborProject : harborProject,
                        traefikNetwork: traefikNetwork
                    )
                }
            }

            // =================================================================
            // STAGE: E2E Tests (against PREPROD)
            // =================================================================
            stage('E2E Tests') {
                when {
                    expression { return env.BRANCH_NAME == 'main' && runE2E }
                }
                steps {
                    runE2ETests(e2eFramework, env.HOST_WORKSPACE, traefikNetwork, preprodDomain)
                }
                post {
                    always {
                        publishE2EReports()
                    }
                }
            }

            // =================================================================
            // STAGE: K6 Performance Tests (against PREPROD)
            // =================================================================
            stage('K6 Performance') {
                when {
                    expression { return env.BRANCH_NAME == 'main' && runK6 }
                }
                steps {
                    runK6Performance([
                        hostWorkspace : env.HOST_WORKSPACE,
                        traefikNetwork: traefikNetwork,
                        preprodDomain : preprodDomain,
                        k6Vus         : k6Vus,
                        k6Duration    : k6Duration,
                        k6Script      : k6Script
                    ])
                }
                post {
                    always {
                        archiveArtifacts(artifacts: 'k6-results.json, k6-summary.json', allowEmptyArchive: true)
                    }
                }
            }

            // =================================================================
            // STAGE: Approval PROD
            // =================================================================
            stage('Approval PROD') {
                when {
                    expression { return deployProd && (env.TAG_NAME ==~ /v\d+\.\d+\.\d+.*/) }
                }
                steps {
                    script {
                        String approver = input(
                            message: "Deploy ${projectName} ${env.IMAGE_TAG} to PRODUCTION?",
                            submitterParameter: 'approver',
                            submitter: 'admin,maintainers',
                            parameters: [
                                booleanParam(
                                    name: 'confirm',
                                    defaultValue: false,
                                    description: 'Check to confirm production deployment'
                                )
                            ]
                        )
                        echo "Production deployment approved by: ${approver}"
                    }
                }
            }

            // =================================================================
            // STAGE: Deploy PROD (Blue/Green)
            // =================================================================
            stage('Deploy PROD') {
                when {
                    expression { return deployProd && (env.TAG_NAME ==~ /v\d+\.\d+\.\d+.*/) }
                }
                steps {
                    deployBlueGreen(
                        projectName   : projectName,
                        environment   : 'prod',
                        imageTag      : env.IMAGE_TAG,
                        domain        : prodDomain,
                        port          : appPort,
                        registry      : registry,
                        harborProject : harborProject,
                        traefikNetwork: traefikNetwork,
                        monitorMinutes: 15
                    )
                }
            }
        } // end stages

        // =====================================================================
        // Post-build actions
        // =====================================================================
        post {
            // TEMPORAIRE (debug pipeline en cours) : cleanupWorkspace() déplacé de
            // `always` vers `success` uniquement, pour que "Restart from Stage" ait
            // un workspace intact à réutiliser après un échec. A REMETTRE dans
            // `always {}` une fois le pipeline stabilisé, sinon les workspaces des
            // builds échoués s'accumulent sur le disque Jenkins sans être nettoyés.
            success {
                script {
                    cleanupWorkspace(env.HOST_WORKSPACE)
                    notifyBuild('SUCCESS', projectName)
                }
            }

            failure {
                script {
                    notifyBuild('FAILURE', projectName)
                }
            }

            unstable {
                script {
                    notifyBuild('UNSTABLE', projectName)
                }
            }
        }
    }
}

// =============================================================================
// Helper: Deploy a container with Traefik labels
// =============================================================================
void deployContainer(Map args) {
    String containerName = "${args.projectName}-${args.environment}"
    String harborProject = args.harborProject ?: 'furious-ducks'
    String fullImage = "${args.registry}/${harborProject}/${args.projectName}:${args.imageTag}"
    String nodeEnv = args.environment == 'prod' ? 'production' : args.environment

    sh """
        echo "\${HARBOR_CREDENTIALS_PSW}" | docker login ${args.registry} \
            -u "\${HARBOR_CREDENTIALS_USR}" --password-stdin
        docker pull ${fullImage}
        docker logout ${args.registry}
        docker stop ${containerName} 2>/dev/null || true
        docker rm ${containerName} 2>/dev/null || true

        docker run -d \
            --name ${containerName} \
            --restart unless-stopped \
            --network ${args.traefikNetwork} \
            --label "traefik.enable=true" \
            --label "traefik.http.routers.${containerName}.rule=Host(\\`${args.domain}\\`)" \
            --label "traefik.http.routers.${containerName}.entrypoints=websecure" \
            --label "traefik.http.routers.${containerName}.tls.certresolver=letsencrypt" \
            --label "traefik.http.services.${containerName}.loadbalancer.server.port=${args.port}" \
            --label "traefik.http.routers.${containerName}.middlewares=secure-headers@file" \
            -e NODE_ENV=${nodeEnv} \
            -e APP_ENV=${args.environment} \
            ${fullImage}

        echo "Waiting for ${containerName} to become healthy..."
        for i in \$(seq 1 30); do
            if docker exec ${containerName} curl -sf http://localhost:${args.port}/health > /dev/null 2>&1; then
                echo "${containerName} is healthy"
                break
            fi
            if [ "\$i" -eq 30 ]; then
                echo "ERROR: ${containerName} failed health check after 30 attempts"
                docker logs ${containerName}
                exit 1
            fi
            sleep 2
        done
    """
}

// =============================================================================
// Helper: Send build notifications (Mattermost + Email)
// =============================================================================
void notifyBuild(String status, String projectName) {
    Map<String, String> emojiMap = [
        'SUCCESS' : ':white_check_mark:',
        'FAILURE' : ':x:',
        'UNSTABLE': ':warning:'
    ]
    String emoji = emojiMap[status] ?: ':question:'

    String summary = "${emoji} **${projectName}** - Build #${env.BUILD_NUMBER} **${status}**\n" +
        "Branch: '${env.BRANCH_NAME ?: 'N/A'}'\n" +
        "Commit: '${env.GIT_COMMIT_MSG ?: 'N/A'}'\n" +
        "Duration: ${currentBuild.durationString?.replace(' and counting', '') ?: 'N/A'}\n" +
        "[View Build](${env.BUILD_URL})"

    echo summary

    if (status == 'FAILURE') {
        try {
            mattermostSend(color: '#FF0000', message: summary)
        } catch (Exception e) {
            echo "Mattermost notification failed: ${e.message}"
        }

        try {
        emailext(
            subject: "[CI/CD] ${projectName} - Build #${env.BUILD_NUMBER} FAILED",
            body: """
                <h2>${projectName} - Build Failed</h2>
                <p><strong>Branch:</strong> ${env.BRANCH_NAME ?: 'N/A'}</p>
                <p><strong>Commit:</strong> ${env.GIT_COMMIT_MSG ?: 'N/A'}</p>
                <p><strong>Duration:</strong> ${currentBuild.durationString ?: 'N/A'}</p>
                <p><a href="${env.BUILD_URL}">View Build</a></p>
                <p><a href="${env.BUILD_URL}console">Console Output</a></p>
            """,
            mimeType: 'text/html',
            recipientProviders: [
                [$class: 'CulpritsRecipientProvider'],
                [$class: 'RequesterRecipientProvider']
            ]
        )
        } catch (Exception e) {
            echo "Email notification failed: ${e.message}"
        }
    }
}

void installDependencies(String stack, String hostWorkspace) {
    switch (stack) {
        case 'node':
            sh """
                docker run --rm \
                    -v "${hostWorkspace}:/work" \
                    -w /work \
                    node:22-bullseye \
                    npm ci --prefer-offline --no-audit
            """
            // Generate the Prisma client only when a schema is present, so the
            // node stack stays generic across projects (not just Prisma apps).
            sh """
                docker run --rm \
                    -v "${hostWorkspace}:/work" \
                    -w /work \
                    node:22-bullseye \
                    bash -c 'if [ -f api/prisma/schema.prisma ]; then cd api && npx prisma generate; fi'
            """
            break
        case 'php':
            sh 'composer install --no-interaction --prefer-dist --optimize-autoloader'
            break
        case 'python':
            sh '''
python -m venv .venv
. .venv/bin/activate
pip install --upgrade pip
pip install -r requirements.txt
'''
            break
        case 'ruby':
            sh 'bundle install --jobs 4 --retry 3'
            break
        default:
            echo "Unknown stack '${stack}' - skipping dependency install"
    }
}

void runLint(String stack, String hostWorkspace) {
    switch (stack) {
        case 'node':
            sh """
                docker run --rm \
                    -v "${hostWorkspace}:/work" \
                    -w /work \
                    node:22-bullseye \
                    bash -lc '
                        set -e
                        for dir in api webapp; do
                            if [ -f "\$dir/.eslintrc.json" ] || \
                               [ -f "\$dir/.eslintrc.js" ] || \
                               [ -f "\$dir/eslint.config.js" ]; then
                                echo "=== Linting \$dir ==="
                                cd "\$dir"
                                npx eslint "{src,apps,libs,test}/**/*.ts" --format stylish
                                cd ..
                            fi
                        done
                    '
            """
            break
        case 'php':
            sh 'vendor/bin/phpstan analyse --memory-limit=512M --error-format=table'
            break
        case 'python':
            sh '''
. .venv/bin/activate
pip install flake8
flake8 . --count --show-source --statistics
'''
            break
        case 'ruby':
            sh 'bundle exec rubocop --format simple'
            break
        default:
            echo "No linter configured for stack '${stack}'"
    }
}

void runUnitTests(String stack, String hostWorkspace) {
    switch (stack) {
        case 'node':
            sh """
                docker run --rm \
                    -v "${hostWorkspace}:/work" \
                    -w /work \
                    node:22-bullseye \
                    bash -lc '
                        set -e
                        for dir in api webapp; do
                            if [ -f "\$dir/jest.config.js" ] || [ -f "\$dir/jest.config.ts" ]; then
                                echo "=== Testing \$dir ==="
                                cd "\$dir"
                                npx jest --ci --coverage --reporters=default --reporters=jest-junit
                                cd ..
                            fi
                        done
                    '
            """
            break
        case 'php':
            sh 'vendor/bin/phpunit --log-junit reports/junit.xml --coverage-clover reports/coverage.xml'
            break
        case 'python':
            sh '''
. .venv/bin/activate
pip install pytest pytest-cov
pytest --junitxml=reports/junit.xml --cov=. --cov-report=xml:reports/coverage.xml
'''
            break
        case 'ruby':
            sh 'bundle exec rspec --format documentation --format RspecJunitFormatter --out reports/junit.xml'
            break
        default:
            echo "No test runner configured for stack '${stack}'"
    }
}

void publishJUnitReports() {
    junit(
        testResults: '**/reports/junit.xml, **/junit.xml',
        allowEmptyResults: true
    )
}

void runSonarAnalysis(String stack, String sonarProject) {
    List<String> sonarArgsList = [
        "-Dsonar.projectKey=${sonarProject}",
        "-Dsonar.projectName=${sonarProject}",
        "-Dsonar.host.url=${SONAR_HOST_URL}",
        "-Dsonar.login=${SONAR_AUTH_TOKEN}",
        '-Dsonar.javascript.node.maxspace=4096',
        '-Dsonar.exclusions=**/node_modules/**,**/coverage/**,**/.prisma/**,' +
            '**/dist/**,**/cypress/**,**/*.spec.ts,**/*.cy.ts'
    ]

    switch (stack) {
        case 'node':
            sonarArgsList += [
                '-Dsonar.javascript.lcov.reportPaths=api/coverage/lcov.info,webapp/coverage/lcov.info',
                '-Dsonar.junit.reportPaths=api/junit.xml,webapp/junit.xml'
            ]
            break
        case 'php':
            sonarArgsList += [
                '-Dsonar.php.coverage.reportPaths=reports/coverage.xml',
                '-Dsonar.php.tests.reportPath=reports/junit.xml'
            ]
            break
        case 'python':
            sonarArgsList += ['-Dsonar.python.coverage.reportPaths=reports/coverage.xml']
            break
    }

    String scannerHome = tool 'SonarScanner'
    String scannerArgs = sonarArgsList.join(' ')
    sh "${scannerHome}/bin/sonar-scanner ${scannerArgs}"
}

void waitForQualityGateResult() {
    try {
        timeout(time: 5, unit: 'MINUTES') {
            waitForQualityGate abortPipeline: false
        }
    } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
        echo 'Quality Gate timeout - continuing pipeline (non-blocking on develop)'
        currentBuild.result = 'UNSTABLE'
    }
}

void runBuildAssets(String hostWorkspace) {
    sh """
        docker run --rm \
            -v "${hostWorkspace}:/work" \
            -w /work \
            node:22-bullseye \
            bash -lc 'npm run build'
    """
}

void pushDockerImage(String registry, String dockerImage, String imageTag, boolean pushLatest = false) {
    sh """
        echo "\${HARBOR_CREDENTIALS_PSW}" | docker login ${registry} \
            -u "\${HARBOR_CREDENTIALS_USR}" --password-stdin
        docker push ${dockerImage}:${imageTag}
        docker logout ${registry}
    """
    if (pushLatest) {
        sh """
            echo "\${HARBOR_CREDENTIALS_PSW}" | docker login ${registry} \
                -u "\${HARBOR_CREDENTIALS_USR}" --password-stdin
            docker push ${dockerImage}:latest
            docker logout ${registry}
        """
    }
}

void trivyScan(String dockerImage, String imageTag, String hostWorkspace) {
    // .trivyignore is optional and lives in the client repo, not in this shared
    // library - each project documents/justifies its own accepted CVEs.
    boolean hasIgnoreFile = fileExists('.trivyignore')
    String ignoreFileArg = hasIgnoreFile ? '--ignorefile /work/.trivyignore' : ''

    int trivyExit = sh(
        script: """
            docker run --rm \
                -v /var/run/docker.sock:/var/run/docker.sock \
                -v "${hostWorkspace}:/work" \
                aquasec/trivy:latest image \
                --exit-code 1 \
                --severity CRITICAL \
                ${ignoreFileArg} \
                --no-progress \
                --format table \
                ${dockerImage}:${imageTag}
        """,
        returnStatus: true
    )
    if (trivyExit != 0) {
        sh """
            docker run --rm \
                -v /var/run/docker.sock:/var/run/docker.sock \
                -v "${hostWorkspace}:/work" \
                aquasec/trivy:latest image \
                --severity HIGH,CRITICAL \
                ${ignoreFileArg} \
                --no-progress \
                --format json \
                -o /work/trivy-report.json \
                ${dockerImage}:${imageTag} || true
        """
        error('Trivy found CRITICAL vulnerabilities - see report for details')
    }
    echo 'Trivy scan passed - no CRITICAL vulnerabilities found'
}

void runSemgrep(String hostWorkspace) {
    int semgrepExit = sh(
        script: """
            docker run --rm \
                -v "${hostWorkspace}:/src" \
                returntocorp/semgrep:latest \
                semgrep scan \
                --config=auto \
                --json \
                --output=/src/semgrep-report.json \
                /src
        """,
        returnStatus: true
    )
    archiveArtifacts artifacts: 'semgrep-report.json', allowEmptyArchive: true
    if (semgrepExit != 0) {
        unstable('Semgrep found security issues - review semgrep-report.json')
    }
}

void runGitleaks(String hostWorkspace) {
    int gitleaksExit = sh(
        script: """
            docker run --rm \
                -v "${hostWorkspace}:/path" \
                zricethezav/gitleaks:latest \
                detect \
                --source=/path \
                --report-path=/path/gitleaks-report.json \
                --report-format=json
        """,
        returnStatus: true
    )
    archiveArtifacts artifacts: 'gitleaks-report.json', allowEmptyArchive: true
    if (gitleaksExit != 0) {
        error('Gitleaks detected secrets in the codebase - review gitleaks-report.json')
    }
    echo 'Gitleaks passed - no secrets detected'
}

void runE2ETests(String framework, String hostWorkspace, String traefikNetwork, String devDomain) {
    if (framework == 'playwright') {
        sh """
            docker run --rm \
                -v "${hostWorkspace}:/work" \
                -w /work \
                --network ${traefikNetwork} \
                -e BASE_URL=https://${devDomain} \
                mcr.microsoft.com/playwright:v1.48.0-jammy \
                npx playwright test --reporter=junit,html
        """
        return
    }

    sh """
        docker run --rm \
            -v "${hostWorkspace}:/e2e" \
            -w /e2e \
            --network ${traefikNetwork} \
            -e CYPRESS_BASE_URL=https://${devDomain} \
            cypress/included:latest \
            --reporter junit \
            --reporter-options "mochaFile=reports/e2e-junit.xml"
    """
}

void publishE2EReports() {
    junit(
        testResults: '**/e2e-junit.xml, **/results.xml, **/test-results/**/*.xml',
        allowEmptyResults: true
    )
    publishHTML(target: [
        allowMissing         : true,
        alwaysLinkToLastBuild: true,
        keepAll              : true,
        reportDir            : 'playwright-report',
        reportFiles          : 'index.html',
        reportName           : 'E2E Test Report'
    ])
}

void runK6Performance(Map config) {
    String hostWorkspace = config.hostWorkspace
    String traefikNetwork = config.traefikNetwork
    String preprodDomain = config.preprodDomain
    int k6Vus = config.k6Vus
    String k6Duration = config.k6Duration
    String k6Script = config.k6Script

    sh """
        docker run --rm \
            -v "${hostWorkspace}:/scripts" \
            --network ${traefikNetwork} \
            -e BASE_URL=https://${preprodDomain} \
            -e K6_VUS=${k6Vus} \
            -e K6_DURATION=${k6Duration} \
            grafana/k6:latest run \
            --vus ${k6Vus} \
            --duration ${k6Duration} \
            --out json=/scripts/k6-results.json \
            --summary-export=/scripts/k6-summary.json \
            /scripts/${k6Script}
    """
}

void cleanupWorkspace(String hostWorkspace) {
    sh 'docker image prune -f || true'

    if (hostWorkspace) {
        sh """
            docker run --rm \
                -v "${hostWorkspace}:/work" \
                node:22-bullseye \
                bash -lc 'chmod -R a+rwX /work 2>/dev/null || true'
        """
    }

    if (fileExists('reports')) {
        publishHTML(target: [
            allowMissing         : false,
            alwaysLinkToLastBuild: true,
            keepAll              : true,
            reportDir            : 'reports',
            reportFiles          : 'index.html',
            reportName           : 'Build Reports'
        ])
    } else {
        echo 'No HTML reports found, skipping publishHTML'
    }

    archiveArtifacts(
        artifacts: 'reports/**, trivy-report.json, semgrep-report.json, gitleaks-report.json',
        allowEmptyArchive: true
    )

    cleanWs(
        cleanWhenNotBuilt: false,
        deleteDirs: true,
        disableDeferredWipeout: true
    )
}
