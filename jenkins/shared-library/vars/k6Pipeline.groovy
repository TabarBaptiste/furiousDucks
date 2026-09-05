#!/usr/bin/env groovy
// =============================================================================
// Furious Ducks CI/CD - Standalone K6 Load Test Pipeline
//
// K6 used to run as a stage inside ciPipeline, on every push to main. That cost
// ~3 minutes per build while blocking the branch executor, and produced a
// result nobody compared to anything: a load test is only meaningful as a
// trend. This job runs it on a schedule (and on demand, with overrides),
// against an already-deployed environment - it has no dependency on the
// build that produced it.
//
// Usage (in a project's Jenkinsfile.k6):
//   @Library('furious-ducks-shared-library') _
//   k6Pipeline(
//       projectName : 'my-app',
//       targetUrl   : 'https://my-app-preprod.wk-archi-o24a-4-5-g1.fr',
//       k6Script    : 'tests/performance/load-test.js',
//       k6Vus       : 50,
//       k6Duration  : '3m',
//       cron        : 'H 2 * * 1'   // every Monday around 02:00
//   )
// =============================================================================

void call(Map config = [:]) {
    String projectName    = config.projectName    ?: env.JOB_NAME
    String targetUrl      = config.targetUrl
    String k6Script       = config.k6Script       ?: 'tests/performance/load-test.js'
    int defaultVus        = config.k6Vus          ?: 50
    String defaultDuration = config.k6Duration    ?: '3m'
    String traefikNetwork = config.traefikNetwork ?: 'workflow-network'
    // 'H' spreads the load across the hour so several projects scheduled on the
    // same day do not all start their run at exactly 02:00.
    String cronSchedule   = config.cron           ?: 'H 2 * * 1'

    if (!targetUrl) {
        error("k6Pipeline: missing required parameter 'targetUrl'")
    }

    pipeline {
        agent any

        parameters {
            string(
                name: 'TARGET_URL',
                defaultValue: targetUrl,
                description: 'Environnement à charger. Par défaut la PREPROD du projet.'
            )
            string(
                name: 'K6_VUS',
                defaultValue: "${defaultVus}",
                description: 'Nombre d\'utilisateurs virtuels simultanés.'
            )
            string(
                name: 'K6_DURATION',
                defaultValue: defaultDuration,
                description: 'Durée du palier de charge (ex: 3m, 10m).'
            )
            string(
                name: 'K6_SCRIPT',
                defaultValue: k6Script,
                description: 'Chemin du script k6 dans le dépôt.'
            )
        }

        triggers {
            cron(cronSchedule)
        }

        options {
            timestamps()
            disableConcurrentBuilds()
            // Deliberately longer than the CI pipeline's retention: the value of
            // a load test is the trend across weeks, so the build records are
            // kept well beyond the artefacts they produced.
            buildDiscarder(logRotator(numToKeepStr: '60', artifactNumToKeepStr: '20'))
            timeout(time: 60, unit: 'MINUTES')
            ansiColor('xterm')
        }

        stages {
            stage('Prepare') {
                steps {
                    script {
                        // Translate the Jenkins container path to the host path,
                        // so the k6 container can mount the workspace.
                        String jenkinsHomeHost = env.JENKINS_HOME_HOST ?:
                            '/var/lib/docker/volumes/common-jenkins-data-ci/_data'
                        env.HOST_WORKSPACE = env.WORKSPACE.replace('/var/jenkins_home', jenkinsHomeHost)
                        echo "Target: ${params.TARGET_URL}"
                        echo "Charge: ${params.K6_VUS} VUs pendant ${params.K6_DURATION}"
                    }
                }
            }

            stage('Load Test') {
                steps {
                    runK6LoadTest([
                        hostWorkspace : env.HOST_WORKSPACE,
                        traefikNetwork: traefikNetwork,
                        targetUrl     : params.TARGET_URL,
                        k6Vus         : params.K6_VUS,
                        k6Duration    : params.K6_DURATION,
                        k6Script      : params.K6_SCRIPT
                    ])
                }
                post {
                    always {
                        // Only the summary is archived. The raw --out json file
                        // holds one record per data point - a 3-minute run at 50
                        // VUs produces ~100k requests and a file in the hundreds
                        // of MB, which is not worth keeping 20 generations of.
                        archiveArtifacts(
                            artifacts: 'k6-summary.json',
                            allowEmptyArchive: true
                        )
                    }
                }
            }
        }

        post {
            always {
                script {
                    sh 'rm -f k6-results.json || true'
                }
            }
            failure {
                script {
                    notifyK6('FAILURE', projectName, params.TARGET_URL,
                        params.K6_VUS, params.K6_DURATION)
                }
            }
            unstable {
                script {
                    notifyK6('UNSTABLE', projectName, params.TARGET_URL,
                        params.K6_VUS, params.K6_DURATION)
                }
            }
        }
    }
}

// =============================================================================
// Helper: Run the k6 container against an already-deployed environment
// =============================================================================
void runK6LoadTest(Map args) {
    String k6Script = args.k6Script

    // Say what is missing instead of letting k6 fail on an unreadable path
    // deep inside a docker run.
    if (!fileExists(k6Script)) {
        error("Le script K6 '${k6Script}' est introuvable dans ce projet. " +
            'Ajoutez-le, ou corrigez le paramètre K6_SCRIPT.')
    }

    sh """
        docker run --rm \
            --user 0:0 \
            -v "${args.hostWorkspace}:/scripts" \
            --network ${args.traefikNetwork} \
            -e BASE_URL=${args.targetUrl} \
            -e K6_VUS=${args.k6Vus} \
            -e K6_DURATION=${args.k6Duration} \
            grafana/k6:latest run \
            --vus ${args.k6Vus} \
            --duration ${args.k6Duration} \
            --out json=/scripts/k6-results.json \
            --summary-export=/scripts/k6-summary.json \
            /scripts/${k6Script}
    """

    // grafana/k6 exits 0 even when every VU failed to reach the target (DNS
    // failure, network unreachable, etc.) as long as the configured thresholds
    // evaluate true on zero samples - which they do, since "p(95)<2000" on an
    // empty set is vacuously true. That let a build pass while never actually
    // sending a single request. Count recorded samples in the raw JSON output
    // and fail loudly if there are none, instead of trusting the exit code.
    if (!fileExists('k6-results.json')) {
        error('K6 n\'a produit aucun k6-results.json - le run a échoué avant d\'enregistrer le moindre échantillon.')
    }

    int sampleCount = sh(
        script: 'grep -c \'"type":"Point"\' k6-results.json || true',
        returnStdout: true
    ).trim() as Integer

    if (sampleCount == 0) {
        error("K6 s'est terminé sans erreur mais n'a enregistré aucun échantillon " +
            "(0 requête a atteint ${args.targetUrl}). Le test de charge n'a pas " +
            'réellement tourné - vérifiez la connectivité réseau depuis l\'agent Jenkins.')
    }
}

// =============================================================================
// Helper: Report a failed or degraded load test to Mattermost
// A scheduled job nobody watches is only useful if it speaks up on its own.
// =============================================================================
void notifyK6(String status, String projectName, String targetUrl, String vus, String duration) {
    String emoji = (status == 'FAILURE') ? ':x:' : ':warning:'
    String statusLabel = (status == 'FAILURE') ? 'ÉCHEC' : 'INSTABLE'
    String summary = "${emoji} **${projectName}** - Test de charge K6 #${env.BUILD_NUMBER} **${statusLabel}**\n" +
        "Cible : ${targetUrl}\n" +
        "Charge : ${vus} VUs / ${duration}\n" +
        "Durée : ${currentBuild.durationString?.replace(' and counting', '') ?: 'N/A'}\n" +
        "[Voir le build](${env.BUILD_URL})"

    echo summary

    try {
        mattermostSend(color: (status == 'FAILURE') ? '#D00000' : '#FFA500', message: summary)
    } catch (Exception e) {
        echo "Notification Mattermost échouée: ${e.message}"
    }
}
