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
//       // Exclusions Sonar ajoutees aux exclusions par defaut de la library
//       // (le sonar.exclusions du sonar-project.properties est ignore) :
//       sonarExtraExclusions: ['**/*.stories.tsx'],
//       deployDev   : true,
//       deployPreprod: true,
//       deployProd  : true,
//       runE2E      : true,
//       devDomain   : 'my-app-dev.wk-archi-o24a-4-5-g1.fr',
//       preprodDomain: 'my-app-preprod.wk-archi-o24a-4-5-g1.fr',
//       prodDomain  : 'my-app.dsp5-archi-o24a-4-5-g1.fr',
//       appPort     : 3000,
//
//       // Load testing is NOT part of this pipeline: it runs as its own
//       // scheduled job, see vars/k6Pipeline.groovy.
//
//       // Optional: separate backend API, deployed alongside the frontend.
//       // Wired for DEV and PREPROD - PROD follows once validated.
//       apiDockerfile : 'api/Dockerfile',
//       apiPort       : 3001,
//       apiHealthPath : '/api/health',
//       devApiDomain    : 'my-app-api-dev.wk-archi-o24a-4-5-g1.fr',
//       preprodApiDomain: 'my-app-api-preprod.wk-archi-o24a-4-5-g1.fr',
//       dbCredentialsId: 'my-app-db-credentials',   // Jenkins "username/password" cred
//                                                    // -> also used to build DATABASE_URL
//                                                    //    and REDIS_HOST/PORT automatically
//       apiEnv        : [API_PREFIX: 'api'],        // environment-INDEPENDENT vars only:
//                                                    // FRONTEND_URL and CORS_ORIGINS are
//                                                    // derived from the target environment's
//                                                    // frontend domain (see apiEnvFor)
//       apiSecretEnv  : [                             // pulled from Jenkins credentials
//           [envVar: 'JWT_SECRET', credentialId: 'my-app-jwt-secret']
//       ]
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
    // Exclusions Sonar additionnelles, fusionnees avec la liste par defaut.
    // Le sonar.exclusions du sonar-project.properties du projet est ignore :
    // l'argument CLI passe par le scanner est prioritaire.
    List sonarExtraExclusions = config.sonarExtraExclusions ?: []
    String stack          = config.stack           ?: 'node'

    // Deployment flags
    boolean deployDev      = config.deployDev      != false
    boolean deployPreprod  = config.deployPreprod   != false
    boolean deployProd     = config.deployProd      ?: false

    // Test flags
    boolean runE2E         = config.runE2E         ?: false
    // OWASP ZAP baseline scan against the deployed PREPROD site (CST 3.2 lists
    // Trivy + Gitleaks + OWASP as the PREPROD security barrier).
    boolean runZap         = config.runZap         ?: false

    // Notification recipients per environment, e.g.
    //   [preprod: 'po@client.tld,techlead@agency.tld', prod: '...']
    // Environments absent from the map fall back to the default Jenkins
    // recipient providers (commit authors + build requester).
    Map<String, String> notifyRecipients = config.notifyRecipients ?: [:]

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

    // E2E settings
    String e2eFramework   = config.e2eFramework   ?: 'playwright'  // 'playwright' or 'cypress'

    // Docker network for Traefik
    String traefikNetwork = config.traefikNetwork ?: 'workflow-network'

    // -------------------------------------------------------------------------
    // Optional separate backend API (wired for DEV and PREPROD)
    // -------------------------------------------------------------------------
    String apiDockerfile  = config.apiDockerfile
    boolean hasApi        = apiDockerfile != null
    int apiPort           = config.apiPort         ?: 3001
    String apiHealthPath  = config.apiHealthPath   ?: '/api/health'
    String devApiDomain   = config.devApiDomain    ?: (hasApi ? "${projectName}-api-dev.wk-archi-o24a-4-5-g1.fr" : null)
    String preprodApiDomain = config.preprodApiDomain ?:
        (hasApi ? "${projectName}-api-preprod.wk-archi-o24a-4-5-g1.fr" : null)
    // PROD API stays on the wk zone, not dsp5: dsp5 only hosts the frontend
    // and has no wildcard certificate, while wk has one.
    String prodApiDomain = config.prodApiDomain ?:
        (hasApi ? "${projectName}-api.wk-archi-o24a-4-5-g1.fr" : null)
    String dbCredentialsId = config.dbCredentialsId ?: "${projectName}-db-credentials"
    Map<String, String> apiEnv = config.apiEnv ?: [:]
    List apiSecretEnv     = config.apiSecretEnv ?: []

    // Per-environment credential overrides. The CST (2.9) requires a distinct
    // credential set per environment; projects that start on a single shared set
    // can split them later by filling these maps, without touching the library.
    // Any environment left out falls back to the shared dbCredentialsId /
    // apiSecretEnv above.
    //   dbCredentialsByEnv: [preprod: 'my-app-db-credentials-preprod']
    //   apiSecretEnvByEnv : [preprod: [[envVar: 'JWT_SECRET', credentialId: '...-preprod']]]
    Map dbCredentialsByEnv = config.dbCredentialsByEnv ?: [:]
    Map apiSecretEnvByEnv  = config.apiSecretEnvByEnv  ?: [:]

    // Extra --build-arg pairs for the webapp image, same override pattern as
    // dbCredentialsByEnv/apiSecretEnvByEnv above: common values apply to every
    // environment, webappBuildArgsByEnv[env] is merged on top and wins on
    // conflict. Lets a Jenkinsfile ship e.g. an analytics ID only in PROD
    // without touching this library.
    //   webappBuildArgsByEnv: [prod: [NEXT_PUBLIC_GA_ID: 'G-XXXX']]
    Map webappBuildArgs      = config.webappBuildArgs      ?: [:]
    Map webappBuildArgsByEnv = config.webappBuildArgsByEnv ?: [:]

    // Whether the webapp image is environment-specific, and must therefore be
    // rebuilt for PROD rather than promoted from PREPROD.
    //
    // The CST (2.4) promises a single image promoted unchanged from DEV to
    // PROD. That holds for any service that reads its configuration at
    // startup - the API does. A Next.js build does not: every NEXT_PUBLIC_*
    // value is inlined into the bundle it emits, so an image built for PREPROD
    // carries the PREPROD API URL and site URL for good. Promoting it would
    // point the production frontend at the preprod API, and the participations
    // of real users at the preprod database.
    //
    // Defaults to false, so projects whose frontend image is environment-
    // agnostic keep the promotion path exactly as it was. Next.js projects
    // set it to true.
    boolean webappIsEnvSpecific = config.webappIsEnvSpecific ?: false

    // Front-end domain per environment, so the API's FRONTEND_URL/CORS_ORIGINS and
    // the webapp's NEXT_PUBLIC_API_URL can be derived from the target environment
    // instead of being hard-coded to DEV by the calling Jenkinsfile.
    Map<String, String> frontDomains = [dev: devDomain, preprod: preprodDomain, prod: prodDomain]
    Map<String, String> apiDomains   = [dev: devApiDomain, preprod: preprodApiDomain, prod: prodApiDomain]

    // -------------------------------------------------------------------------
    // Pipeline Definition
    // -------------------------------------------------------------------------
    pipeline {
        agent any

        options {
            timestamps()
            disableConcurrentBuilds()
            // Deux retentions distinctes, parce que les deux choses ne pesent pas
            // le meme poids : les logs et metadonnees d'un build sont legers et
            // servent a la tracabilite, les artefacts (rapports Trivy/Semgrep/
            // Gitleaks, rapport HTML Playwright) sont lourds et ne servent qu'au
            // diagnostic recent. On garde donc l'historique plus longtemps que
            // les fichiers. Meme principe que k6Pipeline.groovy (60/20), calibre
            // plus court ici : la CI tourne a chaque push, le job k6 une fois
            // par semaine.
            buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '10'))
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
            // STAGE: Prepare
            // Jenkins has already checked the repository out in its implicit
            // "Declarative: Checkout SCM" stage, so this one no longer calls
            // checkout scm - that second fetch was pure duplication. What is
            // left is the stage's real job: deriving the build metadata every
            // later stage reads (image tag, host workspace, target env).
            // =================================================================
            stage('Prepare') {
                steps {
                    script {
                        env.GIT_COMMIT_SHORT = sh(
                            script: 'git rev-parse --short HEAD',
                            returnStdout: true
                        ).trim()
                        env.GIT_COMMIT_MSG = sh(
                            script: 'git log -1 --pretty=%s',
                            returnStdout: true
                        ).trim()
                        env.GIT_COMMIT_AUTHOR = sh(
                            script: 'git log -1 --pretty=%an',
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

                        // Single source of truth for "which environment does this
                        // build target", derived once here and read by every
                        // environment-scoped stage below. Empty on branches that
                        // deploy nowhere (feature/*, release/*, ...).
                        env.DEPLOY_ENV = deployEnvFor(env.BRANCH_NAME, env.TAG_NAME) ?: ''
                        echo "Deploy environment: ${env.DEPLOY_ENV ?: '(none)'}"
                    }
                }
            }

            // =================================================================
            // STAGE: Install Dependencies
            // Sur le chemin PROD, le seul stage qui consomme les node_modules
            // est "Run Prisma Migrations" : il resout le CLI Prisma a la version
            // du lockfile, et la base PROD est un conteneur dedie qui nait vide
            // (voir dbContainerFor) - rien n'est repris de PREPROD, donc les
            // migrations y sont indispensables. Sans API (hasApi == false), un
            // build de tag n'a plus aucun consommateur : Lint, Unit Tests et
            // Sonar sont tous hors du chemin PROD. On n'installe donc rien.
            // =================================================================
            stage('Install Dependencies') {
                when {
                    expression { return env.DEPLOY_ENV != 'prod' || hasApi }
                }
                steps {
                    installDependencies(stack, env.HOST_WORKSPACE)
                }
            }

            // =================================================================
            // STAGE: Lint
            // Hors chemin PROD : un tag ne reconstruit rien (voir "Promote
            // Image"), il promeut l'image deja construite et deja lintee sur
            // main. Relinter le meme commit ne peut rien apprendre de neuf.
            // =================================================================
            stage('Lint') {
                when {
                    expression { return env.DEPLOY_ENV != 'prod' }
                }
                steps {
                    runLint(stack, env.HOST_WORKSPACE)
                }
            }

            // =================================================================
            // STAGE: Unit Tests
            // Hors chemin PROD uniquement, pour la meme raison que Lint : un tag
            // promeut un commit deja teste sur main (build once, deploy
            // everywhere). Les branches de travail (feature/*, release/*,
            // hotfix/*) sont incluses : les dependances sont deja installees,
            // le cout est marginal, et c'est le seul retour automatise qu'une
            // branche obtient avant sa merge request vers develop.
            // =================================================================
            stage('Unit Tests') {
                when {
                    expression { return env.DEPLOY_ENV != 'prod' }
                }
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
            // Reserve aux branches qui deploient quelque part (develop/main).
            // Exclu des branches de travail pour une raison technique et non de
            // cout : SonarQube tourne en edition Community, qui ne connait pas
            // l'analyse par branche. Toute analyse, quelle que soit sa branche
            // d'origine, ecrase les metriques du projet unique - un scan de
            // feature/* ferait donc mentir le Quality Gate de develop et de
            // main. Lint et Unit Tests, eux, restent joues sur ces branches.
            // Exclu du chemin PROD pour la meme raison que Lint/Unit Tests : un
            // tag promeut l'image de main sans rien reconstruire, et le commit
            // a deja ete analyse a ce moment-la.
            // =================================================================
            stage('SonarQube Analysis') {
                when {
                    expression { return env.DEPLOY_ENV in ['dev', 'preprod'] }
                }
                steps {
                    withSonarQubeEnv('SonarQube') {
                        runSonarAnalysis(stack, sonarProject, sonarExtraExclusions)
                    }
                }
            }

            // =================================================================
            // STAGE: Quality Gate
            // =================================================================
            stage('Quality Gate') {
                when {
                    expression { return env.DEPLOY_ENV in ['dev', 'preprod'] }
                }
                steps {
                    waitForQualityGateResult(env.DEPLOY_ENV)
                }
            }

            // =================================================================
            // STAGE: Gitleaks (Secret Detection)
            // Runs BEFORE any image is built or pushed. It used to sit after
            // Docker Push, which meant a leaked secret was only reported once
            // the image containing it was already published to Harbor.
            //
            // Joue des les branches de travail : un secret pousse sur feature/*
            // est deja compromis et devra etre revoque, donc c'est le seul
            // controle dont la valeur depend strictement de sa precocite -
            // l'apprendre a la merge request vers develop est deja trop tard.
            //
            // Hors chemin PROD : un build de tag ne modifie pas l'arbre source.
            // Il promeut l'image "main-<sha>", dont l'existence dans Harbor
            // prouve que main a construit et scanne exactement ce commit. Re-
            // scanner le meme arbre avec les memes regles ne peut rien trouver
            // de neuf.
            // =================================================================
            stage('Gitleaks') {
                when {
                    expression { return env.DEPLOY_ENV != 'prod' }
                }
                steps {
                    runGitleaks(env.HOST_WORKSPACE)
                }
            }

            // =================================================================
            // STAGE: Semgrep SAST (Static Application Security Testing)
            // Same rationale as Gitleaks: this used to run after Deploy DEV,
            // so the static analysis never had a chance to stop anything.
            // Cantonne a PREPROD : c'est la porte d'entree vers la production,
            // et le dernier point ou l'arbre source change encore. DEV est
            // ecarte pour le rythme des pushs (deja couvert par Sonar), et le
            // chemin PROD pour la meme raison que Gitleaks - source identique a
            // celle deja analysee sur main. Semgrep ne pose de toute facon
            // qu'un `unstable` : sur un tag il n'aurait meme pas le pouvoir
            // d'arreter le deploiement qu'il precede.
            // =================================================================
            stage('Semgrep SAST') {
                when {
                    expression { return env.DEPLOY_ENV == 'preprod' }
                }
                steps {
                    runSemgrep(env.HOST_WORKSPACE)
                }
            }

            // =================================================================
            // STAGE: Docker Build
            // The "Build Assets" stage that used to sit here was removed: it
            // ran `npm run build` at the workspace root, but the Dockerfile is
            // multi-stage and rebuilds from source anyway, so its output was
            // never consumed. It also filled the workspace, inflating the
            // Docker build context to ~1 GB and invalidating the COPY layer.
            //
            // PROD is normally excluded: a tag build promotes the already-built,
            // already-scanned PREPROD image instead of rebuilding from source
            // (see "Promote Image" below) - build once, deploy everywhere.
            // It is included only when webappIsEnvSpecific says the frontend
            // bundle cannot be reused across environments.
            // =================================================================
            stage('Docker Build') {
                when {
                    expression { return env.DEPLOY_ENV in ['dev', 'preprod'] || (webappIsEnvSpecific && env.DEPLOY_ENV == 'prod') }
                }
                steps {
                    script {
                        // NEXT_PUBLIC_API_URL is a Next.js *build-time* variable, baked
                        // into the client bundle - it must be passed as a --build-arg
                        // here, not as a runtime -e on `docker run` (deployContainer()).
                        // It must point at the API domain of the environment this image
                        // is being built for, otherwise the PREPROD bundle ships either
                        // no API URL at all or the DEV one.
                        Map<String, String> resolvedBuildArgs = [:]
                        String targetApiDomain = apiDomains[env.DEPLOY_ENV]
                        if (hasApi && targetApiDomain) {
                            resolvedBuildArgs['NEXT_PUBLIC_API_URL'] = "https://${targetApiDomain}/api/v1"
                        }
                        // Same rationale as NEXT_PUBLIC_API_URL above: baked into the
                        // client bundle at build time, so it must match the domain of
                        // the environment being built, not just DEV.
                        String targetFrontDomain = frontDomains[env.DEPLOY_ENV]
                        if (targetFrontDomain) {
                            resolvedBuildArgs['NEXT_PUBLIC_SITE_URL'] = "https://${targetFrontDomain}"
                        }
                        // Project-supplied build args: common values first, then the
                        // per-environment override on top (see webappBuildArgsByEnv).
                        resolvedBuildArgs.putAll(webappBuildArgs)
                        resolvedBuildArgs.putAll(webappBuildArgsByEnv[env.DEPLOY_ENV] ?: [:])
                        echo "Building ${env.DOCKER_IMAGE}:${env.IMAGE_TAG}"
                        buildDockerImage(
                            dockerImage   : env.DOCKER_IMAGE,
                            imageTag      : env.IMAGE_TAG,
                            dockerfilePath: dockerfilePath,
                            buildArgs     : resolvedBuildArgs
                        )
                    }
                }
            }

            // =================================================================
            // STAGE: Docker Build API (optional separate backend, DEV + PREPROD)
            // =================================================================
            stage('Docker Build API') {
                when {
                    expression { return hasApi && env.DEPLOY_ENV in ['dev', 'preprod'] }
                }
                steps {
                    script {
                        echo "Building ${env.DOCKER_IMAGE}-api:${env.IMAGE_TAG}"
                        buildDockerImage(
                            dockerImage   : "${env.DOCKER_IMAGE}-api",
                            imageTag      : env.IMAGE_TAG,
                            dockerfilePath: apiDockerfile
                        )
                    }
                }
            }

            // =================================================================
            // STAGE: Trivy Security Scan
            // Runs BEFORE the push so a vulnerable image never reaches Harbor.
            // PROD excluded: the tag build promotes the PREPROD image bit-for-
            // bit (see "Promote Image"), and that same image was already
            // scanned when it was built for PREPROD - rescanning identical
            // bits adds nothing. When webappIsEnvSpecific is set the PROD image
            // is freshly built instead, so it has never been scanned and must
            // be.
            // =================================================================
            stage('Trivy Scan') {
                when {
                    expression { return env.DEPLOY_ENV in ['dev', 'preprod'] || (webappIsEnvSpecific && env.DEPLOY_ENV == 'prod') }
                }
                steps {
                    trivyScan(env.DOCKER_IMAGE, env.IMAGE_TAG, env.HOST_WORKSPACE)
                }
            }

            // =================================================================
            // STAGE: Trivy Security Scan API (optional separate backend, DEV + PREPROD)
            // =================================================================
            stage('Trivy Scan API') {
                when {
                    expression { return hasApi && env.DEPLOY_ENV in ['dev', 'preprod'] }
                }
                steps {
                    trivyScan("${env.DOCKER_IMAGE}-api", env.IMAGE_TAG, env.HOST_WORKSPACE)
                }
            }

            // =================================================================
            // STAGE: Docker Push (to Harbor registry)
            // PROD excluded: "Promote Image" below does its own push, of the
            // re-tagged PREPROD image rather than one built in this run. Unless
            // webappIsEnvSpecific is set, in which case this run did build the
            // PROD frontend image and has to push it itself.
            // =================================================================
            stage('Docker Push') {
                when {
                    expression { return env.DEPLOY_ENV in ['dev', 'preprod'] || (webappIsEnvSpecific && env.DEPLOY_ENV == 'prod') }
                }
                steps {
                    pushDockerImage(registry, env.DOCKER_IMAGE, env.IMAGE_TAG, env.DEPLOY_ENV == 'dev')
                }
            }

            // =================================================================
            // STAGE: Docker Push API (optional separate backend, DEV + PREPROD)
            // =================================================================
            stage('Docker Push API') {
                when {
                    expression { return hasApi && env.DEPLOY_ENV in ['dev', 'preprod'] }
                }
                steps {
                    pushDockerImage(registry, "${env.DOCKER_IMAGE}-api", env.IMAGE_TAG, env.DEPLOY_ENV == 'dev')
                }
            }

            // =================================================================
            // STAGE: Promote Image (PROD only)
            // Build once, deploy everywhere: a tag build re-tags and pushes the
            // exact image already built, scanned and validated for PREPROD on
            // this same commit, instead of rebuilding from source. The source
            // tag is reconstructed the same way "Prepare" builds it for a
            // branch build: "main-<short-sha>".
            //
            // The frontend is skipped when webappIsEnvSpecific is set: its
            // PREPROD image carries PREPROD URLs baked into the bundle, so it
            // was rebuilt for PROD earlier in this run and pushed by "Docker
            // Push". The backend is always promoted - it reads its whole
            // configuration from the environment at startup, so the very bits
            // validated on PREPROD are the ones that run in PROD.
            // =================================================================
            stage('Promote Image') {
                when {
                    expression { return env.DEPLOY_ENV == 'prod' && !(webappIsEnvSpecific && !hasApi) }
                }
                steps {
                    script {
                        String preprodTag = "main-${env.GIT_COMMIT_SHORT}"
                        if (webappIsEnvSpecific) {
                            echo "Frontend rebuilt for PROD (webappIsEnvSpecific), not promoted"
                        } else {
                            echo "Promoting ${env.DOCKER_IMAGE}:${preprodTag} to ${env.IMAGE_TAG}"
                            promoteImage(registry, env.DOCKER_IMAGE, preprodTag, env.IMAGE_TAG)
                        }
                        if (hasApi) {
                            echo "Promoting ${env.DOCKER_IMAGE}-api:${preprodTag} to ${env.IMAGE_TAG}"
                            promoteImage(registry, "${env.DOCKER_IMAGE}-api", preprodTag, env.IMAGE_TAG)
                        }
                    }
                }
            }

            // =================================================================
            // STAGE: Approval PROD
            // Place juste apres "Promote Image" et avant "Ensure Database" :
            // c'est la frontiere entre ce qui est inerte et ce qui ne l'est
            // plus. Tout ce qui precede se contente de preparer un artefact
            // dans Harbor, sans toucher a la production qui tourne. Tout ce qui
            // suit modifie l'etat reel : creation de base, migrations Prisma
            // (irreversibles), bascule Blue/Green.
            //
            // Ce stage se trouvait auparavant apres les migrations : un refus
            // de l'approbateur laissait donc le schema de la base de production
            // deja migre, face a une application non deployee - et
            // `scripts/rollback.sh` remettait l'ancien conteneur devant un
            // schema en avance sur lui.
            // =================================================================
            stage('Approval PROD') {
                when {
                    expression { return env.DEPLOY_ENV == 'prod' && deployProd }
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
            // STAGE: Ensure Database (optional separate backend, DEV + PREPROD + PROD)
            // Postgres is shared across DEV/PREPROD - one container, one
            // database per environment - and provisioned once, idempotently:
            // never recreated on every build, since that would wipe data.
            // Redis gets a dedicated container per environment instead, so the
            // two environments cannot collide on the same keyspace (sessions,
            // cache). PROD gets its own isolated Postgres + Redis containers
            // (see dbContainerFor), never shared with DEV/PREPROD.
            // =================================================================
            stage('Ensure Database') {
                when {
                    expression { return hasApi && deployFor(env.DEPLOY_ENV, deployDev, deployPreprod, deployProd) }
                }
                steps {
                    withCredentials([usernamePassword(
                        credentialsId   : credentialIdFor(dbCredentialsByEnv, env.DEPLOY_ENV, dbCredentialsId),
                        usernameVariable: 'DB_USER',
                        passwordVariable: 'DB_PASSWORD'
                    )]) {
                        ensureSharedDatabase(
                            postgresContainer: dbContainerFor(projectName, env.DEPLOY_ENV),
                            redisContainer   : redisContainerFor(projectName, env.DEPLOY_ENV),
                            dbName           : "${projectName}_${env.DEPLOY_ENV}",
                            traefikNetwork   : traefikNetwork
                        )
                    }
                }
            }

            // =================================================================
            // STAGE: Run Prisma Migrations (optional separate backend, DEV + PREPROD + PROD)
            // Applies pending migrations to the freshly-provisioned database
            // before the API container starts, so it doesn't crash hitting
            // tables that don't exist yet on a brand new database.
            // =================================================================
            stage('Run Prisma Migrations') {
                when {
                    expression { return hasApi && deployFor(env.DEPLOY_ENV, deployDev, deployPreprod, deployProd) }
                }
                steps {
                    withCredentials([usernamePassword(
                        credentialsId   : credentialIdFor(dbCredentialsByEnv, env.DEPLOY_ENV, dbCredentialsId),
                        usernameVariable: 'DB_USER',
                        passwordVariable: 'DB_PASSWORD'
                    )]) {
                        runPrismaMigrations(
                            hostWorkspace    : env.HOST_WORKSPACE,
                            traefikNetwork   : traefikNetwork,
                            postgresContainer: dbContainerFor(projectName, env.DEPLOY_ENV),
                            dbName           : "${projectName}_${env.DEPLOY_ENV}"
                        )
                    }
                }
            }

            // =================================================================
            // STAGE: Deploy DEV API (optional separate backend, DEV only)
            // =================================================================
            stage('Deploy DEV API') {
                when {
                    expression { return env.DEPLOY_ENV == 'dev' && deployDev && hasApi }
                }
                steps {
                    script {
                        deployApiContainer(
                            projectName      : projectName,
                            environment      : 'dev',
                            apiDomain        : devApiDomain,
                            apiPort          : apiPort,
                            apiHealthPath    : apiHealthPath,
                            imageTag         : env.IMAGE_TAG,
                            registry         : registry,
                            harborProject    : harborProject,
                            traefikNetwork   : traefikNetwork,
                            apiEnv           : apiEnvFor(apiEnv, frontDomains['dev']),
                            apiSecretEnv     : apiSecretEnvFor(apiSecretEnvByEnv, 'dev', apiSecretEnv),
                            dbCredentialsId  : credentialIdFor(dbCredentialsByEnv, 'dev', dbCredentialsId),
                            postgresContainer: "${projectName}-db-shared",
                            redisContainer   : redisContainerFor(projectName, 'dev'),
                            dbName           : "${projectName}_dev"
                        )
                    }
                }
            }

            // =================================================================
            // STAGE: Deploy DEV
            // =================================================================
            stage('Deploy DEV') {
                when {
                    expression { return env.DEPLOY_ENV == 'dev' && deployDev }
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
            // STAGE: Deploy PREPROD API (optional separate backend)
            // Deployed before the frontend so the latter never serves a page
            // whose API calls have nothing to answer them.
            // =================================================================
            stage('Deploy PREPROD API') {
                when {
                    expression { return env.DEPLOY_ENV == 'preprod' && deployPreprod && hasApi }
                }
                steps {
                    script {
                        deployApiContainer(
                            projectName      : projectName,
                            environment      : 'preprod',
                            apiDomain        : preprodApiDomain,
                            apiPort          : apiPort,
                            apiHealthPath    : apiHealthPath,
                            imageTag         : env.IMAGE_TAG,
                            registry         : registry,
                            harborProject    : harborProject,
                            traefikNetwork   : traefikNetwork,
                            apiEnv           : apiEnvFor(apiEnv, frontDomains['preprod']),
                            apiSecretEnv     : apiSecretEnvFor(apiSecretEnvByEnv, 'preprod', apiSecretEnv),
                            dbCredentialsId  : credentialIdFor(dbCredentialsByEnv, 'preprod', dbCredentialsId),
                            postgresContainer: dbContainerFor(projectName, 'preprod'),
                            redisContainer   : redisContainerFor(projectName, 'preprod'),
                            dbName           : "${projectName}_preprod"
                        )
                    }
                }
            }

            // =================================================================
            // STAGE: Deploy PREPROD
            // Deployed before E2E/ZAP so those suites validate the freshly
            // promoted PREPROD build (iso-prod), per the CST deployment process.
            // =================================================================
            stage('Deploy PREPROD') {
                when {
                    expression { return env.DEPLOY_ENV == 'preprod' && deployPreprod }
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
                    expression { return env.DEPLOY_ENV == 'preprod' && runE2E }
                }
                steps {
                    runE2ETests(e2eFramework, env.HOST_WORKSPACE, traefikNetwork, preprodDomain)
                }
                post {
                    always {
                        publishE2EReports(e2eFramework)
                    }
                }
            }

            // =================================================================
            // STAGE: OWASP ZAP Baseline (against PREPROD)
            // Third leg of the PREPROD security barrier required by the CST
            // (Trivy on images, Gitleaks on secrets, OWASP on the running site).
            // Unlike Trivy/Gitleaks, this one probes the *deployed* application,
            // so it runs after Deploy PREPROD rather than on the source tree.
            // =================================================================
            stage('OWASP ZAP') {
                when {
                    expression { return env.DEPLOY_ENV == 'preprod' && runZap }
                }
                steps {
                    runZapBaseline(env.HOST_WORKSPACE, traefikNetwork, preprodDomain)
                }
                post {
                    always {
                        archiveArtifacts(
                            artifacts: 'zap-report.html, zap-report.json',
                            allowEmptyArchive: true
                        )
                    }
                }
            }

            // =================================================================
            // STAGE: Deploy PROD API (optional separate backend, Blue/Green)
            // Deployed before the frontend, same rationale as the DEV/PREPROD
            // API stages: the frontend must never go live pointing at an API
            // that isn't there yet.
            // =================================================================
            stage('Deploy PROD API') {
                when {
                    expression { return hasApi && env.DEPLOY_ENV == 'prod' && deployProd }
                }
                steps {
                    script {
                        deployApiBlueGreen(
                            projectName      : projectName,
                            environment      : 'prod',
                            apiDomain        : prodApiDomain,
                            apiPort          : apiPort,
                            apiHealthPath    : apiHealthPath,
                            imageTag         : env.IMAGE_TAG,
                            registry         : registry,
                            harborProject    : harborProject,
                            traefikNetwork   : traefikNetwork,
                            apiEnv           : apiEnvFor(apiEnv, frontDomains['prod']),
                            apiSecretEnv     : apiSecretEnvFor(apiSecretEnvByEnv, 'prod', apiSecretEnv),
                            dbCredentialsId  : credentialIdFor(dbCredentialsByEnv, 'prod', dbCredentialsId),
                            postgresContainer: dbContainerFor(projectName, 'prod'),
                            redisContainer   : redisContainerFor(projectName, 'prod'),
                            dbName           : "${projectName}_prod",
                            monitorMinutes   : 15
                        )
                    }
                }
            }

            // =================================================================
            // STAGE: Deploy PROD (Blue/Green)
            // =================================================================
            stage('Deploy PROD') {
                when {
                    expression { return env.DEPLOY_ENV == 'prod' && deployProd }
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
            // cleanupWorkspace() archive les rapports (Trivy, Semgrep, Gitleaks,
            // HTML) AVANT de vider le workspace : il doit donc tourner quel que
            // soit le resultat du build. Cantonne a `success`, un build qui
            // echouait sur une CVE critique Trivy n'archivait jamais le rapport
            // Trivy qui explique l'echec, et son workspace restait sur le disque
            // Jenkins indefiniment. Les logs console, eux, sont captures par
            // Jenkins independamment de ce bloc et restent consultables.
            always {
                script {
                    cleanupWorkspace(env.HOST_WORKSPACE)
                }
            }

            success {
                script {
                    notifyBuild('SUCCESS', projectName, env.DEPLOY_ENV,
                        notifyRecipients[env.DEPLOY_ENV] ?: '')
                }
            }

            failure {
                script {
                    notifyBuild('FAILURE', projectName, env.DEPLOY_ENV)
                }
            }

            unstable {
                script {
                    notifyBuild('UNSTABLE', projectName, env.DEPLOY_ENV)
                }
            }
        }
    }
}

// =============================================================================
// Helper: Percent-encode a credential for safe use inside a connection URL
// (postgresql://user:password@host:port/db). Without this, a DB password
// containing reserved URL characters (: @ / % # [ ]) silently produces a
// malformed connection string - this is what caused Prisma's "P1013 invalid
// port number" failure on thetiptop.
// =============================================================================
String urlEncode(String value) {
    return java.net.URLEncoder.encode(value, 'UTF-8').replace('+', '%20')
}

// =============================================================================
// Helper: Which environment a build targets, derived from its branch or tag,
// per the GitFlow mapping in the CST (develop -> DEV, main -> PREPROD,
// semver tag -> PROD). Returns null for branches that deploy nowhere
// (feature/*, release/*, hotfix/* before they are merged).
// =============================================================================
String deployEnvFor(String branchName, String tagName) {
    if ((tagName ?: '') ==~ /v\d+\.\d+\.\d+.*/) { return 'prod' }
    if (branchName == 'main')    { return 'preprod' }
    if (branchName == 'develop') { return 'dev' }
    return null
}

// =============================================================================
// Helper: Is deployment enabled for this environment? Lets the shared
// DEV/PREPROD stages honour the caller's per-environment deploy toggles
// without duplicating the stage for each environment.
// =============================================================================
boolean deployFor(String environment, boolean deployDev, boolean deployPreprod, boolean deployProd) {
    if (environment == 'dev')     { return deployDev }
    if (environment == 'preprod') { return deployPreprod }
    if (environment == 'prod')    { return deployProd }
    return false
}

// =============================================================================
// Helper: Redis container name for an environment. DEV was provisioned before
// PREPROD existed, under the "-shared" name; it keeps that name so an existing
// DEV Redis is never recreated (which would drop its data). Every other
// environment gets its own container, so DEV and PREPROD cannot collide on the
// same keyspace.
// =============================================================================
String redisContainerFor(String projectName, String environment) {
    return environment == 'dev' ? "${projectName}-redis-shared" : "${projectName}-redis-${environment}"
}

// =============================================================================
// Helper: Postgres container name for an environment. DEV and PREPROD share
// the same instance (one container, one database per environment - see
// 'Ensure Database'). PROD always gets its own dedicated container, isolated
// from DEV/PREPROD, since a shared PROD database is not an acceptable risk.
// =============================================================================
String dbContainerFor(String projectName, String environment) {
    return environment == 'prod' ? "${projectName}-db-prod" : "${projectName}-db-shared"
}

// =============================================================================
// Helper: Build the API's environment variables for a target environment.
// FRONTEND_URL and CORS_ORIGINS always point at that environment's own
// frontend, so they are derived here rather than hard-coded by the calling
// Jenkinsfile - which previously pinned both to the DEV domain, making the
// API reject the PREPROD frontend on CORS. A caller that sets either key
// explicitly still wins.
// =============================================================================
Map<String, String> apiEnvFor(Map<String, String> baseEnv, String frontDomain) {
    return [
        FRONTEND_URL: "https://${frontDomain}",
        CORS_ORIGINS: "https://${frontDomain}"
    ] + (baseEnv ?: [:])
}

// =============================================================================
// Helper: Resolve a per-environment credential override, falling back to the
// shared one. See dbCredentialsByEnv / apiSecretEnvByEnv in the config block.
// =============================================================================
String credentialIdFor(Map byEnv, String environment, String fallback) {
    return byEnv?.get(environment) ?: fallback
}

List apiSecretEnvFor(Map byEnv, String environment, List fallback) {
    return byEnv?.get(environment) ?: fallback
}

// =============================================================================
// Helper: OWASP ZAP baseline scan against a deployed environment.
// Passive scan only (no attack traffic), which is what a baseline run is for:
// it crawls the site and reports misconfigurations and missing security
// headers. Findings mark the build UNSTABLE rather than failing it, matching
// how Semgrep is already handled - the CST makes the scan mandatory, not its
// findings blocking.
// =============================================================================
void runZapBaseline(String hostWorkspace, String traefikNetwork, String targetDomain) {
    int zapExit = sh(
        script: """
            docker run --rm \
                -v "${hostWorkspace}:/zap/wrk:rw" \
                --network ${traefikNetwork} \
                ghcr.io/zaproxy/zaproxy:stable \
                zap-baseline.py \
                -t https://${targetDomain} \
                -r zap-report.html \
                -J zap-report.json
        """,
        returnStatus: true
    )
    // 0 = clean, 2 = warnings only, 1/3 = failures or a broken run.
    if (zapExit == 0) {
        echo 'OWASP ZAP baseline passed - no findings'
    } else {
        unstable("OWASP ZAP baseline reported findings (exit ${zapExit}) - review zap-report.html")
    }
}

// =============================================================================
// Helper: Deploy a container with Traefik labels
//
// The post-deploy health check probes the container from a throwaway curl
// sidecar on the same Docker network, rather than `docker exec ... curl`
// inside the container: client images are expected to be minimal (see the
// cahier des specifications) and cannot be assumed to ship curl - a
// node:*-alpine image does not, which silently failed thetiptop's API deploy
// while the app itself was perfectly healthy.
// =============================================================================
void deployContainer(Map args) {
    String containerName = args.containerName ?: "${args.projectName}-${args.environment}"
    String harborProject = args.harborProject ?: 'furious-ducks'
    String imageProjectName = args.imageProjectName ?: args.projectName
    String fullImage = "${args.registry}/${harborProject}/${imageProjectName}:${args.imageTag}"
    // PREPROD runs in production mode too: the CST requires it to be iso-prod
    // (same images, same runtime configuration), and Next.js/Nest only apply
    // their production behaviour when NODE_ENV is literally 'production'.
    String nodeEnv = args.environment in ['prod', 'preprod'] ? 'production' : args.environment
    String healthPath = args.healthPath ?: '/health'
    Map<String, String> extraEnv = args.extraEnv ?: [:]
    String extraEnvFlags = extraEnv.collect { k, v -> "-e ${k}=\"${v}\"" }.join(' ')

    // Tag currently in service, captured before the container is replaced. It is
    // written back as a deploy.previous-tag label so both the auto-rollback below
    // and scripts/rollback.sh can find the version to fall back to without the
    // operator having to know it. deployBlueGreen.groovy already did this for
    // PROD; DEV/PREPROD carried no deploy.* labels at all, which left
    // rollback.sh's auto-detection with nothing to read.
    String previousTag = sh(
        script: "docker inspect --format='{{index .Config.Labels \"deploy.tag\"}}' ${containerName} 2>/dev/null || true",
        returnStdout: true
    ).trim()
    if (previousTag == '<no value>') { previousTag = '' }

    sh """
        echo "\${HARBOR_CREDENTIALS_PSW}" | docker login ${args.registry} \
            -u "\${HARBOR_CREDENTIALS_USR}" --password-stdin
        docker pull ${fullImage}
        docker logout ${args.registry}
        docker stop ${containerName} 2>/dev/null || true
        docker rm ${containerName} 2>/dev/null || true

        set +x
        docker run -d \
            --name ${containerName} \
            --restart unless-stopped \
            --network ${args.traefikNetwork} \
            --label "traefik.enable=true" \
            --label "traefik.http.routers.${containerName}.rule=Host(\\`${args.domain}\\`)" \
            --label "traefik.http.routers.${containerName}.entrypoints=websecure" \
            --label "traefik.http.routers.${containerName}.tls.certresolver=letsencrypt" \
            --label "traefik.http.services.${containerName}.loadbalancer.server.port=${args.port}" \
            --label "traefik.http.routers.${containerName}.middlewares=secure-headers@file,compress@file" \
            --label "deploy.project=${imageProjectName}" \
            --label "deploy.environment=${args.environment}" \
            --label "deploy.tag=${args.imageTag}" \
            --label "deploy.previous-tag=${previousTag}" \
            -e NODE_ENV=${nodeEnv} \
            -e APP_ENV=${args.environment} \
            ${extraEnvFlags} \
            ${fullImage}
        set -x
    """

    int healthExit = sh(
        script: """
            echo "Waiting for ${containerName} to become healthy..."
            for i in \$(seq 1 30); do
                if docker run --rm --network ${args.traefikNetwork} curlimages/curl:latest \
                    -sf "http://${containerName}:${args.port}${healthPath}" > /dev/null 2>&1; then
                    echo "${containerName} is healthy"
                    exit 0
                fi
                sleep 2
            done
            echo "ERROR: ${containerName} failed health check after 30 attempts"
            docker logs --tail 100 ${containerName} || true
            exit 1
        """,
        returnStatus: true
    )

    if (healthExit == 0) { return }

    // The CST requires an automatic rollback on a failed health check in DEV and
    // PREPROD (under a minute). Without a previous tag - a first deployment -
    // there is nothing to fall back to, so the build simply fails as before.
    if (!previousTag) {
        error("${containerName} failed its health check and no previous version " +
            'is recorded to roll back to (first deployment?).')
    }

    echo "${containerName} failed its health check - rolling back to ${previousTag}"
    String rollbackImage = "${args.registry}/${harborProject}/${imageProjectName}:${previousTag}"
    sh """
        echo "\${HARBOR_CREDENTIALS_PSW}" | docker login ${args.registry} \
            -u "\${HARBOR_CREDENTIALS_USR}" --password-stdin
        docker pull ${rollbackImage}
        docker logout ${args.registry}
        docker rm -f ${containerName} 2>/dev/null || true

        set +x
        docker run -d \
            --name ${containerName} \
            --restart unless-stopped \
            --network ${args.traefikNetwork} \
            --label "traefik.enable=true" \
            --label "traefik.http.routers.${containerName}.rule=Host(\\`${args.domain}\\`)" \
            --label "traefik.http.routers.${containerName}.entrypoints=websecure" \
            --label "traefik.http.routers.${containerName}.tls.certresolver=letsencrypt" \
            --label "traefik.http.services.${containerName}.loadbalancer.server.port=${args.port}" \
            --label "traefik.http.routers.${containerName}.middlewares=secure-headers@file,compress@file" \
            --label "deploy.project=${imageProjectName}" \
            --label "deploy.environment=${args.environment}" \
            --label "deploy.tag=${previousTag}" \
            --label "deploy.rollback=true" \
            -e NODE_ENV=${nodeEnv} \
            -e APP_ENV=${args.environment} \
            ${extraEnvFlags} \
            ${rollbackImage}
        set -x
    """
    error("${containerName} failed its health check on ${args.imageTag} and was " +
        "rolled back to ${previousTag}.")
}

// =============================================================================
// Helper: Deploy the optional backend API container (DEV and PREPROD)
// Kept separate from the pipeline stage body so the "apiSecretEnv may be
// empty" branching (withCredentials rejects an empty binding list) doesn't
// clutter the Jenkinsfile-visible stage steps.
// =============================================================================
void deployApiContainer(Map args) {
    List apiSecretEnv = args.apiSecretEnv ?: []
    Map<String, String> apiEnv = args.apiEnv ?: [:]
    String dbCredentialsId = args.dbCredentialsId
    String postgresContainer = args.postgresContainer
    String redisContainer = args.redisContainer
    String dbName = args.dbName

    // Reuses the same DB credential as "Ensure DEV Database" to build
    // DATABASE_URL here, so callers never have to touch the DB password
    // themselves just to wire the API's connection string.
    List credBindings = apiSecretEnv.collect { e -> string(credentialsId: e.credentialId, variable: e.envVar) }
    if (dbCredentialsId) {
        credBindings += usernamePassword(
            credentialsId   : dbCredentialsId,
            usernameVariable: 'DB_USER',
            passwordVariable: 'DB_PASSWORD'
        )
    }

    Closure doDeploy = {
        Map<String, String> secretEnv = [:]
        apiSecretEnv.each { e -> secretEnv[e.envVar] = env."${e.envVar}" }
        if (dbCredentialsId) {
            secretEnv['DATABASE_URL'] =
                "postgresql://${urlEncode(env.DB_USER)}:${urlEncode(env.DB_PASSWORD)}@${postgresContainer}:5432/${dbName}?schema=public"
        }
        if (redisContainer) {
            secretEnv['REDIS_HOST'] = redisContainer
            secretEnv['REDIS_PORT'] = '6379'
        }
        deployContainer(
            projectName     : args.projectName,
            containerName   : "${args.projectName}-api-${args.environment}",
            imageProjectName: "${args.projectName}-api",
            environment     : args.environment,
            imageTag        : args.imageTag,
            domain          : args.apiDomain,
            port            : args.apiPort,
            healthPath      : args.apiHealthPath,
            registry        : args.registry,
            harborProject   : args.harborProject,
            traefikNetwork  : args.traefikNetwork,
            extraEnv        : apiEnv + secretEnv
        )
    }

    if (credBindings) {
        withCredentials(credBindings) { doDeploy() }
    } else {
        doDeploy()
    }
}

// =============================================================================
// Helper: Same wiring as deployApiContainer (DATABASE_URL, REDIS_HOST/PORT,
// JWT secrets) but deploys through deployBlueGreen instead of deployContainer -
// used for PROD, where the API gets the same zero-downtime guarantee as the
// frontend rather than a stop/start replace.
// =============================================================================
void deployApiBlueGreen(Map args) {
    List apiSecretEnv = args.apiSecretEnv ?: []
    Map<String, String> apiEnv = args.apiEnv ?: [:]
    String dbCredentialsId = args.dbCredentialsId
    String postgresContainer = args.postgresContainer
    String redisContainer = args.redisContainer
    String dbName = args.dbName

    List credBindings = apiSecretEnv.collect { e -> string(credentialsId: e.credentialId, variable: e.envVar) }
    if (dbCredentialsId) {
        credBindings += usernamePassword(
            credentialsId   : dbCredentialsId,
            usernameVariable: 'DB_USER',
            passwordVariable: 'DB_PASSWORD'
        )
    }

    Closure doDeploy = {
        Map<String, String> secretEnv = [:]
        apiSecretEnv.each { e -> secretEnv[e.envVar] = env."${e.envVar}" }
        if (dbCredentialsId) {
            secretEnv['DATABASE_URL'] =
                "postgresql://${urlEncode(env.DB_USER)}:${urlEncode(env.DB_PASSWORD)}@${postgresContainer}:5432/${dbName}?schema=public"
        }
        if (redisContainer) {
            secretEnv['REDIS_HOST'] = redisContainer
            secretEnv['REDIS_PORT'] = '6379'
        }
        deployBlueGreen(
            projectName    : "${args.projectName}-api",
            environment    : args.environment,
            imageTag       : args.imageTag,
            domain         : args.apiDomain,
            port           : args.apiPort,
            healthPath     : args.apiHealthPath,
            registry       : args.registry,
            harborProject  : args.harborProject,
            traefikNetwork : args.traefikNetwork,
            monitorMinutes : args.monitorMinutes,
            extraEnv       : apiEnv + secretEnv
        )
    }

    if (credBindings) {
        withCredentials(credBindings) { doDeploy() }
    } else {
        doDeploy()
    }
}

// =============================================================================
// Helper: Provision a shared Postgres + Redis pair, idempotently.
// Only creates the containers/database if they don't already exist - never
// stops/recreates them, since (unlike stateless app containers) that would
// destroy data on every build. Generic across projects, not thetiptop-specific.
// Requires DB_USER / DB_PASSWORD in the environment (bind via withCredentials).
// =============================================================================
void ensureSharedDatabase(Map args) {
    String pgContainer = args.postgresContainer
    String redisContainer = args.redisContainer
    String dbName = args.dbName
    String network = args.traefikNetwork

    sh """
        if ! docker ps -a --format '{{.Names}}' | grep -qx "${pgContainer}"; then
            echo "Creating shared Postgres container ${pgContainer}"
            docker run -d \
                --name ${pgContainer} \
                --restart unless-stopped \
                --network ${network} \
                -e POSTGRES_USER="\${DB_USER}" \
                -e POSTGRES_PASSWORD="\${DB_PASSWORD}" \
                -v ${pgContainer}-data:/var/lib/postgresql/data \
                postgres:16-alpine

            echo "Waiting for ${pgContainer} to accept connections..."
            for i in \$(seq 1 30); do
                docker exec ${pgContainer} pg_isready -U "\${DB_USER}" > /dev/null 2>&1 && break
                sleep 2
            done
        else
            echo "${pgContainer} already exists, leaving it untouched"
        fi

        if ! docker exec ${pgContainer} psql -U "\${DB_USER}" -tAc \
            "SELECT 1 FROM pg_database WHERE datname='${dbName}'" | grep -q 1; then
            echo "Creating database ${dbName}"
            docker exec ${pgContainer} psql -U "\${DB_USER}" -c "CREATE DATABASE ${dbName}"
        else
            echo "Database ${dbName} already exists"
        fi

        if ! docker ps -a --format '{{.Names}}' | grep -qx "${redisContainer}"; then
            echo "Creating shared Redis container ${redisContainer}"
            docker run -d \
                --name ${redisContainer} \
                --restart unless-stopped \
                --network ${network} \
                -v ${redisContainer}-data:/data \
                redis:8-alpine redis-server --appendonly yes
        else
            echo "${redisContainer} already exists, leaving it untouched"
        fi
    """
}

// =============================================================================
// Helper: Apply Prisma migrations to the DEV database before the API
// container starts. Runs from the already-installed workspace (node_modules
// were populated in "Install Dependencies"), so `npx prisma migrate deploy`
// resolves the local Prisma CLI rather than needing a fresh install.
// Requires DB_USER / DB_PASSWORD in the environment (bind via withCredentials).
// =============================================================================
void runPrismaMigrations(Map args) {
    String hostWorkspace = args.hostWorkspace
    String traefikNetwork = args.traefikNetwork
    String postgresContainer = args.postgresContainer
    String dbName = args.dbName
    String databaseUrl =
        "postgresql://${urlEncode(env.DB_USER)}:${urlEncode(env.DB_PASSWORD)}@${postgresContainer}:5432/${dbName}?schema=public"

    sh """
        set +x
        docker run --rm \
            -v "${hostWorkspace}:/work" \
            -w /work/api \
            --network ${traefikNetwork} \
            -e DATABASE_URL="${databaseUrl}" \
            node:22-bullseye \
            npx prisma migrate deploy
        set -x
    """
}

// =============================================================================
// Helper: `docker build` with the standard provenance/date/vcs args, plus any
// project-specific --build-arg pairs (e.g. NEXT_PUBLIC_API_URL).
// =============================================================================
void buildDockerImage(Map args) {
    Map<String, String> extraBuildArgs = args.buildArgs ?: [:]
    String extraArgsFlags = extraBuildArgs.collect { k, v -> "--build-arg ${k}=${v}" }.join(' ')

    sh """
        docker build \
            --provenance=false \
            --sbom=false \
            --build-arg BUILD_DATE=\$(date -u +"%Y-%m-%dT%H:%M:%SZ") \
            --build-arg VCS_REF=${env.GIT_COMMIT_SHORT} \
            --build-arg VERSION=${args.imageTag} \
            ${extraArgsFlags} \
            -t ${args.dockerImage}:${args.imageTag} \
            -f ${args.dockerfilePath} \
            .
    """
    if (env.DEPLOY_ENV == 'dev') {
        sh "docker tag ${args.dockerImage}:${args.imageTag} ${args.dockerImage}:latest"
    }
}

// =============================================================================
// Helper: Send build notifications (Mattermost + Email)
// =============================================================================
void notifyBuild(String status, String projectName, String deployEnv = '', String recipients = '') {
    Map<String, String> emojiMap = [
        'SUCCESS' : ':white_check_mark:',
        'FAILURE' : ':x:',
        'UNSTABLE': ':warning:'
    ]
    String emoji = emojiMap[status] ?: ':question:'

    // env.STAGE_NAME reflects the stage that was executing when the pipeline
    // hit this post block, so on failure it points at the stage that failed.
    String failedStageLine = (status == 'FAILURE' && env.STAGE_NAME) ?
        "Stage: '${env.STAGE_NAME}'\n" : ''

    String summary = "${emoji} **${projectName}** - Build #${env.BUILD_NUMBER} **${status}**\n" +
        "Branch: '${env.BRANCH_NAME ?: 'N/A'}'\n" +
        "Commit: '${env.GIT_COMMIT_MSG ?: 'N/A'}' by ${env.GIT_COMMIT_AUTHOR ?: 'N/A'}\n" +
        failedStageLine +
        "Duration: ${currentBuild.durationString?.replace(' and counting', '') ?: 'N/A'}\n" +
        "[View Build](${env.BUILD_URL})"

    echo summary

    // Stakeholder notification: on a successful deployment to an environment
    // that declares recipients (PREPROD -> PO, client, Tech Lead, support per
    // the CST), tell them the environment is ready for functional acceptance.
    // Failures keep going to the people who can act on them (below), not to the
    // client.
    if (status == 'SUCCESS' && recipients) {
        try {
            emailext(
                subject: "[${deployEnv.toUpperCase()}] ${projectName} - version ${env.IMAGE_TAG ?: env.BUILD_NUMBER} déployée",
                body: """
                    <h2>${projectName} - déploiement ${deployEnv.toUpperCase()} réussi</h2>
                    <p>Une nouvelle version vient d'être déployée et est disponible pour la recette.</p>
                    <ul>
                        <li><strong>Version :</strong> ${env.IMAGE_TAG ?: 'N/A'}</li>
                        <li><strong>Branche :</strong> ${env.BRANCH_NAME ?: 'N/A'}</li>
                        <li><strong>Dernier commit :</strong> ${env.GIT_COMMIT_MSG ?: 'N/A'}</li>
                        <li><strong>Build :</strong> <a href="${env.BUILD_URL}">#${env.BUILD_NUMBER}</a></li>
                    </ul>
                    <p>Merci de procéder à la validation fonctionnelle et de remonter
                    toute anomalie à la cheffe de projet.</p>
                """,
                mimeType: 'text/html',
                to: recipients
            )
        } catch (Exception e) {
            echo "Stakeholder notification failed: ${e.message}"
        }
    }

    // PROD deployments go to Mattermost regardless of the stakeholder email
    // above (which only fires when notifyRecipients declares an address for
    // this environment) - going live is operationally significant enough
    // that the team should see it land, not just the client/PO.
    if (status == 'SUCCESS' && deployEnv == 'prod') {
        try {
            mattermostSend(color: '#36A64F', message: summary)
        } catch (Exception e) {
            echo "Mattermost notification failed: ${e.message}"
        }
    }

    // UNSTABLE is how the security gates (Semgrep, ZAP, quality gate) report
    // findings, so it needs the same channel as FAILURE - otherwise a build
    // degraded by security findings is only visible to whoever opens the log.
    if (status == 'UNSTABLE') {
        try {
            mattermostSend(color: '#FFA500', message: summary)
        } catch (Exception e) {
            echo "Mattermost notification failed: ${e.message}"
        }
    }

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
                <p><strong>Commit:</strong> ${env.GIT_COMMIT_MSG ?: 'N/A'} by ${env.GIT_COMMIT_AUTHOR ?: 'N/A'}</p>
                <p><strong>Stage:</strong> ${env.STAGE_NAME ?: 'N/A'}</p>
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

void runSonarAnalysis(String stack, String sonarProject, List extraExclusions = []) {
    List<String> exclusions = ['**/node_modules/**', '**/coverage/**', '**/.prisma/**',
                               '**/dist/**', '**/cypress/**', '**/*.spec.ts', '**/*.cy.ts']
    exclusions += extraExclusions

    List<String> sonarArgsList = [
        "-Dsonar.projectKey=${sonarProject}",
        "-Dsonar.projectName=${sonarProject}",
        "-Dsonar.host.url=${SONAR_HOST_URL}",
        "-Dsonar.login=${SONAR_AUTH_TOKEN}",
        '-Dsonar.javascript.node.maxspace=4096',
        "-Dsonar.exclusions=${exclusions.join(',')}"
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

// The gate's verdict used to be discarded: waitForQualityGate ran with
// abortPipeline: false and nothing ever read the .status it returns, so a
// failing gate never showed up as anything but a green Sonar dashboard link.
// PREPROD/PROD (main/tag) now block on it, matching the CST; DEV (develop)
// keeps the fast, non-blocking feedback a branch still under active work needs.
void waitForQualityGateResult(String deployEnv) {
    boolean blocking = deployEnv in ['preprod', 'prod']
    // Surtout pas de type declare ici : waitForQualityGate renvoie un QGStatus
    // (WaitForQualityGateStep$QGStatus), pas une Map - un type declare
    // declencherait un cast implicite qui echoue systematiquement.
    def qualityGate
    try {
        timeout(time: 5, unit: 'MINUTES') {
            qualityGate = waitForQualityGate(abortPipeline: false)
        }
    } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
        echo 'Quality Gate timeout - continuing pipeline'
        currentBuild.result = 'UNSTABLE'
        return
    }

    if (qualityGate.status == 'OK') {
        echo "SonarQube Quality Gate passed: ${qualityGate.status}"
        return
    }

    if (blocking) {
        error("SonarQube Quality Gate failed: ${qualityGate.status} - blocking deploy to ${deployEnv}")
    } else {
        unstable("SonarQube Quality Gate failed: ${qualityGate.status}")
    }
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

// =============================================================================
// Helper: Promote an already-built, already-scanned image to a new tag,
// without rebuilding it. Used by "Promote Image" so a PROD release ships the
// exact bits validated on PREPROD (build once, deploy everywhere) rather than
// a fresh build of the same source that could drift (base image update,
// non-pinned dependency, ...).
// =============================================================================
void promoteImage(String registry, String dockerImage, String sourceTag, String targetTag) {
    sh """
        echo "\${HARBOR_CREDENTIALS_PSW}" | docker login ${registry} \
            -u "\${HARBOR_CREDENTIALS_USR}" --password-stdin
        docker pull ${dockerImage}:${sourceTag}
        docker tag ${dockerImage}:${sourceTag} ${dockerImage}:${targetTag}
        docker push ${dockerImage}:${targetTag}
        docker logout ${registry}
    """
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
                semgrep/semgrep:1.175.0 \
                semgrep scan \
                --config=auto \
                --error \
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

// The domain argument is whichever environment the suite runs against - DEV or
// PREPROD depending on the calling stage - not necessarily the DEV one.
void runE2ETests(String framework, String hostWorkspace, String traefikNetwork, String targetDomain) {
    // Check the project actually ships an E2E suite before starting the runner.
    // Playwright reports "0 tests" and exits non-zero when it finds none, which
    // surfaces in the build log as a bare "script returned exit code 1" with no
    // hint that the suite is simply missing from the project.
    List<String> configCandidates = framework == 'playwright'
        ? ['playwright.config.ts', 'playwright.config.js', 'playwright.config.mjs',
           'webapp/playwright.config.ts', 'webapp/playwright.config.js']
        : ['cypress.config.ts', 'cypress.config.js', 'cypress.json',
           'webapp/cypress.config.ts', 'webapp/cypress.config.js']

    if (!configCandidates.any { candidate -> fileExists(candidate) }) {
        error("runE2E is enabled but no ${framework} configuration was found in this project " +
            "(looked for: ${configCandidates.join(', ')}). Add an E2E suite, or set " +
            'runE2E: false in the project Jenkinsfile until one exists.')
    }

    if (framework == 'playwright') {
        sh """
            docker run --rm \
                -v "${hostWorkspace}:/work" \
                -w /work \
                --network ${traefikNetwork} \
                -e BASE_URL=https://${targetDomain} \
                mcr.microsoft.com/playwright:v1.48.0-jammy \
                npx playwright test --reporter=junit,html
        """
        return
    }

    // The Cypress config lives under webapp/ in this monorepo layout (same
    // reason Lint/Unit Tests cd into "api" and "webapp"), not at the repo
    // root - mounting the whole workspace but pointing cypress/included at
    // /e2e directly used to fail to find cypress.config.ts. And a plain
    // CYPRESS_BASE_URL env var does NOT override Cypress's `baseUrl` config
    // (Cypress only auto-maps case-exact CYPRESS_<configKey> env vars, e.g.
    // CYPRESS_baseUrl) - it silently created an unused Cypress.env('BASE_URL')
    // instead. `--config baseUrl=...` is the project's own established
    // convention (see webapp's `test:e2e:staging` npm script) and is used
    // here instead, so CI runs the exact same way a developer already does.
    boolean cypressInWebapp = fileExists('webapp/cypress.config.ts') || fileExists('webapp/cypress.config.js')
    String workDir = cypressInWebapp ? '/e2e/webapp' : '/e2e'
    sh """
        docker run --rm \
            -v "${hostWorkspace}:/e2e" \
            -w ${workDir} \
            --network ${traefikNetwork} \
            cypress/included:latest \
            --config baseUrl=https://${targetDomain} \
            --reporter junit \
            --reporter-options "mochaFile=cypress/reports/e2e-junit.xml"
    """
}

void publishE2EReports(String framework) {
    junit(
        testResults: '**/e2e-junit.xml, **/results.xml, **/test-results/**/*.xml',
        allowEmptyResults: true
    )

    // Only Playwright produces an HTML report here - runE2ETests drives Cypress
    // with the JUnit reporter only, so there is no cypress HTML directory to
    // publish and pointing publishHTML at "playwright-report" would silently
    // (allowMissing) publish an empty report.
    if (framework != 'playwright') {
        return
    }

    publishHTML(target: [
        allowMissing         : true,
        alwaysLinkToLastBuild: true,
        keepAll              : true,
        reportDir            : 'playwright-report',
        reportFiles          : 'index.html',
        reportName           : 'E2E Test Report'
    ])
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
