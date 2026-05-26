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
//       prodDomain  : 'my-app.wk-archi-o24a-4-5-g1.fr',
//       appPort     : 3000,
//       k6Vus       : 50,
//       k6Duration  : '3m'
//   )
// =============================================================================

def call(Map config = [:]) {

    // -------------------------------------------------------------------------
    // Default configuration
    // -------------------------------------------------------------------------
    def projectName    = config.projectName    ?: env.JOB_NAME
    def registry       = config.registry       ?: 'registry.wk-archi-o24a-4-5-g1.fr'
    def imageName      = "${registry}/${projectName}"
    def sonarProject   = config.sonarProject   ?: projectName
    def stack          = config.stack           ?: 'node'

    // Deployment flags
    def deployDev      = config.deployDev      != false
    def deployPreprod  = config.deployPreprod   != false
    def deployProd     = config.deployProd      ?: false

    // Test flags
    def runE2E         = config.runE2E         ?: false
    def runK6          = config.runK6          ?: false

    // Domains
    def devDomain      = config.devDomain      ?: "${projectName}-dev.wk-archi-o24a-4-5-g1.fr"
    def preprodDomain  = config.preprodDomain  ?: "${projectName}-preprod.wk-archi-o24a-4-5-g1.fr"
    def prodDomain     = config.prodDomain     ?: "${projectName}.wk-archi-o24a-4-5-g1.fr"

    // Networking
    def appPort        = config.appPort        ?: 3000

    // K6 Performance settings
    def k6Vus          = config.k6Vus          ?: 50
    def k6Duration     = config.k6Duration     ?: '3m'
    def k6Script       = config.k6Script       ?: 'tests/performance/load-test.js'

    // E2E settings
    def e2eFramework   = config.e2eFramework   ?: 'playwright'  // 'playwright' or 'cypress'

    // Docker network for Traefik
    def traefikNetwork = config.traefikNetwork ?: 'workflow-network'

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
            GIT_COMMIT_SHORT   = ''
            IMAGE_TAG          = ''
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
                        def gitTag = sh(
                            script: 'git describe --tags --exact-match 2>/dev/null || true',
                            returnStdout: true
                        ).trim()
                        if (gitTag) {
                            env.IMAGE_TAG = gitTag
                        } else {
                            def safeBranch = env.BRANCH_NAME.replaceAll('[^a-zA-Z0-9._-]', '-')
                            env.IMAGE_TAG = "${safeBranch}-${env.GIT_COMMIT_SHORT}"
                        }
                        echo "Image tag: ${env.IMAGE_TAG}"

                        // Translate Jenkins container path → host path for Docker volume mounts
                        def jenkinsHomeHost = env.JENKINS_HOME_HOST ?: '/var/lib/docker/volumes/common-jenkins-data-ci/_data'
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
                    script {
                        switch (stack) {
                            case 'node':
                                sh """
                                    docker run --rm \
                                        -v "${HOST_WORKSPACE}:/work" \
                                        -w /work \
                                        node:18-bullseye \
                                        npm ci --prefer-offline --no-audit
                                """
                                // Génération du Prisma Client pour que les types TypeScript soient disponibles
                                sh """
                                    docker run --rm \
                                        -v "${HOST_WORKSPACE}:/work" \
                                        -w /work \
                                        node:18-bullseye \
                                        bash -c 'cd api && npx prisma generate'
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
                }
            }

            // =================================================================
            // STAGE: Lint
            // =================================================================
            stage('Lint') {
                steps {
                    script {
                        switch (stack) {
                            case 'node':
                                sh """
                                    docker run --rm \
                                        -v "${HOST_WORKSPACE}:/work" \
                                        -w /work \
                                        node:18-bullseye \
                                        bash -lc '
                                            for dir in api webapp; do
                                                if [ -f "\$dir/.eslintrc.json" ] || [ -f "\$dir/.eslintrc.js" ] || [ -f "\$dir/eslint.config.js" ]; then
                                                    echo "=== Linting \$dir ==="
                                                    cd "\$dir"
                                                    npx eslint "{src,apps,libs,test}/**/*.ts" --format stylish || true
                                                    cd ..
                                                fi
                                            done
                                        '
                                """
                                break
                            case 'php':
                                sh 'vendor/bin/phpstan analyse --memory-limit=512M --error-format=table || true'
                                break
                            case 'python':
                                sh '''
                                    . .venv/bin/activate
                                    pip install flake8
                                    flake8 . --count --show-source --statistics || true
                                '''
                                break
                            case 'ruby':
                                sh 'bundle exec rubocop --format simple || true'
                                break
                            default:
                                echo "No linter configured for stack '${stack}'"
                        }
                    }
                }
            }

            // =================================================================
            // STAGE: Unit Tests
            // =================================================================
            stage('Unit Tests') {
                steps {
                    script {
                        switch (stack) {
                            case 'node':
                                sh """
                                    docker run --rm \
                                        -v "${HOST_WORKSPACE}:/work" \
                                        -w /work \
                                        node:18-bullseye \
                                        bash -lc '
                                            for dir in api webapp; do
                                                if [ -f "\$dir/jest.config.js" ] || [ -f "\$dir/jest.config.ts" ]; then
                                                    echo "=== Testing \$dir ==="
                                                    cd "\$dir"
                                                    npx jest --ci --coverage --reporters=default --reporters=jest-junit || true
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
                }
                post {
                    always {
                        // Publish JUnit results regardless of pass/fail
                        junit(
                            testResults: '**/reports/junit.xml, **/junit.xml',
                            allowEmptyResults: true
                        )
                    }
                }
            }

            // =================================================================
            // STAGE: SonarQube Analysis
            // =================================================================
            stage('SonarQube Analysis') {
                steps {
                    withSonarQubeEnv('SonarQube') {
                        script {
                            // Construire les arguments en liste pour éviter les sauts de ligne
                            // qui provoqueraient l'exécution d'un argument comme commande shell
                            def sonarArgsList = [
                                "-Dsonar.projectKey=${sonarProject}",
                                "-Dsonar.projectName=${sonarProject}",
                                '-Dsonar.sources=.',
                                '-Dsonar.host.url=${SONAR_HOST_URL}',
                                '-Dsonar.login=${SONAR_AUTH_TOKEN}',
                                '-Dsonar.javascript.node.maxspace=4096',
                                '-Dsonar.exclusions=**/node_modules/**,**/coverage/**,**/.prisma/**,**/dist/**,**/cypress/**,**/*.spec.ts,**/*.cy.ts',
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
                                    sonarArgsList += [
                                        '-Dsonar.python.coverage.reportPaths=reports/coverage.xml'
                                    ]
                                    break
                            }

                            def scannerHome = tool 'SonarScanner'
                            // Joindre tous les args avec un espace simple : aucun \n ne peut s'intercaler
                            def scannerArgs = sonarArgsList.join(' ')
                            sh "${scannerHome}/bin/sonar-scanner ${scannerArgs}"
                        }
                    }
                }
            }

            // =================================================================
            // STAGE: Quality Gate
            // =================================================================
            stage('Quality Gate') {
                steps {
                    timeout(time: 15, unit: 'MINUTES') {
                        waitForQualityGate abortPipeline: true
                    }
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
                    sh """
                        docker run --rm \
                            -v "${HOST_WORKSPACE}:/work" \
                            -w /work \
                            node:18-bullseye \
                            bash -lc "npm run build"
                    """
                }
            }

            // =================================================================
            // STAGE: Docker Build
            // =================================================================
            stage('Docker Build') {
                steps {
                    script {
                        echo "Building ${env.DOCKER_IMAGE}:${env.IMAGE_TAG}"
                        sh """
                            docker build \
                                --build-arg BUILD_DATE=\$(date -u +"%Y-%m-%dT%H:%M:%SZ") \
                                --build-arg VCS_REF=${env.GIT_COMMIT_SHORT} \
                                --build-arg VERSION=${env.IMAGE_TAG} \
                                -t ${env.DOCKER_IMAGE}:${env.IMAGE_TAG} \
                                -t ${env.DOCKER_IMAGE}:latest \
                                .
                        """
                    }
                }
            }

            // =================================================================
            // STAGE: Docker Push (to Harbor registry)
            // =================================================================
            stage('Docker Push') {
                steps {
                    script {
                        sh """
                            echo "\${HARBOR_CREDENTIALS_PSW}" | docker login ${registry} \
                                -u "\${HARBOR_CREDENTIALS_USR}" --password-stdin

                            docker push ${env.DOCKER_IMAGE}:${env.IMAGE_TAG}
                            docker push ${env.DOCKER_IMAGE}:latest

                            docker logout ${registry}
                        """
                    }
                }
            }

            // =================================================================
            // STAGE: Trivy Security Scan
            // =================================================================
            stage('Trivy Scan') {
                steps {
                    script {
                        def trivyExit = sh(
                            script: """
                                docker run --rm \
                                    -v /var/run/docker.sock:/var/run/docker.sock \
                                    aquasec/trivy:latest image \
                                    --exit-code 1 \
                                    --severity CRITICAL \
                                    --no-progress \
                                    --format table \
                                    ${env.DOCKER_IMAGE}:${env.IMAGE_TAG}
                            """,
                            returnStatus: true
                        )
                        if (trivyExit != 0) {
                            // Generate a detailed JSON report for archiving
                            sh """
                                docker run --rm \
                                    -v /var/run/docker.sock:/var/run/docker.sock \
                                    aquasec/trivy:latest image \
                                    --severity HIGH,CRITICAL \
                                    --no-progress \
                                    --format json \
                                    -o trivy-report.json \
                                    ${env.DOCKER_IMAGE}:${env.IMAGE_TAG} || true
                            """
                            error("Trivy found CRITICAL vulnerabilities - see report for details")
                        }
                        echo "Trivy scan passed - no CRITICAL vulnerabilities found"
                    }
                }
            }

            // =================================================================
            // STAGE: Deploy DEV
            // =================================================================
            stage('Deploy DEV') {
                when {
                    allOf {
                        branch 'develop'
                        expression { return deployDev }
                    }
                }
                steps {
                    script {
                        echo "Deploying ${env.IMAGE_TAG} to DEV environment"
                        deployContainer(
                            projectName  : projectName,
                            environment  : 'dev',
                            imageTag     : env.IMAGE_TAG,
                            domain       : devDomain,
                            port         : appPort,
                            registry     : registry,
                            traefikNetwork: traefikNetwork
                        )
                    }
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
                    script {
                        def semgrepExit = sh(
                            script: """
                                docker run --rm \
                                    -v "${HOST_WORKSPACE}:/src" \
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
                            unstable("Semgrep found security issues - review semgrep-report.json")
                        }
                    }
                }
            }

            // =================================================================
            // STAGE: Gitleaks (Secret Detection)
            // =================================================================
            stage('Gitleaks') {
                when {
                    branch 'main'
                }
                steps {
                    script {
                        def gitleaksExit = sh(
                            script: """
                                docker run --rm \
                                    -v "${HOST_WORKSPACE}:/path" \
                                    zricethezav/gitleaks:latest \
                                    detect \
                                    --source=/path \
                                    --report-path=/path/gitleaks-report.json \
                                    --report-format=json \
                                    --no-git
                            """,
                            returnStatus: true
                        )
                        archiveArtifacts artifacts: 'gitleaks-report.json', allowEmptyArchive: true
                        if (gitleaksExit != 0) {
                            error("Gitleaks detected secrets in the codebase - review gitleaks-report.json")
                        }
                        echo "Gitleaks passed - no secrets detected"
                    }
                }
            }

            // =================================================================
            // STAGE: E2E Tests
            // =================================================================
            stage('E2E Tests') {
                when {
                    allOf {
                        branch 'main'
                        expression { return runE2E }
                    }
                }
                steps {
                    script {
                        if (e2eFramework == 'playwright') {
                            sh """
                                docker run --rm \
                                    -v "\${HOST_WORKSPACE}:/work" \
                                    -w /work \
                                    --network ${traefikNetwork} \
                                    -e BASE_URL=https://${devDomain} \
                                    mcr.microsoft.com/playwright:v1.48.0-jammy \
                                    npx playwright test --reporter=junit,html
                            """
                        } else {
                            // Cypress
                            sh """
                                docker run --rm \
                                    -v "\${HOST_WORKSPACE}:/e2e" \
                                    -w /e2e \
                                    --network ${traefikNetwork} \
                                    -e CYPRESS_BASE_URL=https://${devDomain} \
                                    cypress/included:latest \
                                    --reporter junit \
                                    --reporter-options "mochaFile=reports/e2e-junit.xml"
                            """
                        }
                    }
                }
                post {
                    always {
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
                }
            }

            // =================================================================
            // STAGE: K6 Performance Tests
            // =================================================================
            stage('K6 Performance') {
                when {
                    allOf {
                        branch 'main'
                        expression { return runK6 }
                    }
                }
                steps {
                    script {
                        sh """
                            docker run --rm \
                                -v "\${HOST_WORKSPACE}:/scripts" \
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
                }
                post {
                    always {
                        archiveArtifacts artifacts: 'k6-results.json, k6-summary.json', allowEmptyArchive: true
                    }
                }
            }

            // =================================================================
            // STAGE: Deploy PREPROD
            // =================================================================
            stage('Deploy PREPROD') {
                when {
                    allOf {
                        branch 'main'
                        expression { return deployPreprod }
                    }
                }
                steps {
                    script {
                        echo "Deploying ${env.IMAGE_TAG} to PREPROD environment"
                        deployContainer(
                            projectName  : projectName,
                            environment  : 'preprod',
                            imageTag     : env.IMAGE_TAG,
                            domain       : preprodDomain,
                            port         : appPort,
                            registry     : registry,
                            traefikNetwork: traefikNetwork
                        )
                    }
                }
            }

            // =================================================================
            // STAGE: Approval PROD
            // =================================================================
            stage('Approval PROD') {
                when {
                    allOf {
                        tag pattern: 'v\\d+\\.\\d+\\.\\d+.*', comparator: 'REGEXP'
                        expression { return deployProd }
                    }
                }
                steps {
                    script {
                        def approver = input(
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
                    allOf {
                        tag pattern: 'v\\d+\\.\\d+\\.\\d+.*', comparator: 'REGEXP'
                        expression { return deployProd }
                    }
                }
                steps {
                    script {
                        echo "Deploying ${env.IMAGE_TAG} to PRODUCTION (Blue/Green)"
                        deployBlueGreen(
                            projectName   : projectName,
                            environment   : 'prod',
                            imageTag      : env.IMAGE_TAG,
                            domain        : prodDomain,
                            port          : appPort,
                            registry      : registry,
                            traefikNetwork: traefikNetwork,
                            monitorMinutes: 15
                        )
                    }
                }
            }

        } // end stages

        // =====================================================================
        // Post-build actions
        // =====================================================================
        post {
            always {
                script {
                    // node('') = built-in node, sans contrainte de label
                    // node('Jenkins') échouait car le built-in node n'a pas ce label
                    node('') {
                        // Clean up dangling Docker images to free disk space
                        sh 'docker image prune -f || true'

                        // Supprimer les fichiers générés par root via Docker
                        if (env.HOST_WORKSPACE) {
                            sh """
                                docker run --rm \
                                    -v "\${HOST_WORKSPACE}:/work" \
                                    node:18-bullseye \
                                    rm -rf \
                                        /work/node_modules /work/api/node_modules /work/webapp/node_modules \
                                        /work/coverage /work/api/coverage /work/webapp/coverage || true
                            """
                        }
                    }
                }

                // Publish HTML reports if they exist
                script {
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
                }

                // Archive any JSON/XML reports
                archiveArtifacts(
                    artifacts: 'reports/**, trivy-report.json, semgrep-report.json, gitleaks-report.json',
                    allowEmptyArchive: true
                )

                // Clean workspace
                cleanWs(
                    cleanWhenNotBuilt: false,
                    deleteDirs: true,
                    disableDeferredWipeout: true,
                    patterns: [
                        [pattern: 'node_modules/**', type: 'EXCLUDE'],
                        [pattern: '.venv/**', type: 'EXCLUDE']
                    ]
                )
            }

            success {
                script {
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

    } // end pipeline
}

// =============================================================================
// Helper: Deploy a container with Traefik labels
// =============================================================================
def deployContainer(Map args) {
    def containerName = "${args.projectName}-${args.environment}"
    def fullImage     = "${args.registry}/${args.projectName}:${args.imageTag}"

    sh """
        # Pull the latest image
        docker pull ${fullImage}

        # Stop and remove existing container (if any)
        docker stop ${containerName} 2>/dev/null || true
        docker rm   ${containerName} 2>/dev/null || true

        # Start the new container with Traefik labels
        docker run -d \
            --name ${containerName} \
            --restart unless-stopped \
            --network ${args.traefikNetwork} \
            --label "traefik.enable=true" \
            --label "traefik.http.routers.${containerName}.rule=Host(\${args.domain})" \
            --label "traefik.http.routers.${containerName}.entrypoints=websecure" \
            --label "traefik.http.routers.${containerName}.tls.certresolver=letsencrypt" \
            --label "traefik.http.services.${containerName}.loadbalancer.server.port=${args.port}" \
            --label "traefik.http.routers.${containerName}.middlewares=security-headers@file" \
            -e NODE_ENV=${args.environment == 'prod' ? 'production' : args.environment} \
            -e APP_ENV=${args.environment} \
            ${fullImage}

        # Wait for health check
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
def notifyBuild(String status, String projectName) {
    def colorMap = [
        'SUCCESS' : 'good',
        'FAILURE' : 'danger',
        'UNSTABLE': 'warning'
    ]
    def emojiMap = [
        'SUCCESS' : ':white_check_mark:',
        'FAILURE' : ':x:',
        'UNSTABLE': ':warning:'
    ]
    def color = colorMap[status] ?: '#808080'
    def emoji = emojiMap[status] ?: ':question:'

    def summary = """${emoji} **${projectName}** - Build #${env.BUILD_NUMBER} **${status}**
Branch: '${env.BRANCH_NAME ?: 'N/A'}'
Commit: '${env.GIT_COMMIT_SHORT ?: 'N/A'}'
Duration: ${currentBuild.durationString?.replace(' and counting', '') ?: 'N/A'}
[View Build](${env.BUILD_URL})"""

    // Mattermost notification: plugin not present in this image — skip
    echo "Mattermost notification skipped (plugin not installed)"

    // Email notification on failure
    if (status == 'FAILURE') {
        try {
            emailext(
                subject: "[CI/CD] ${projectName} - Build #${env.BUILD_NUMBER} FAILED",
                body: """
                    <h2>${projectName} - Build Failed</h2>
                    <p><strong>Branch:</strong> ${env.BRANCH_NAME ?: 'N/A'}</p>
                    <p><strong>Commit:</strong> ${env.GIT_COMMIT_SHORT ?: 'N/A'}</p>
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
