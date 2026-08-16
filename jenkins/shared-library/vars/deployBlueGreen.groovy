#!/usr/bin/env groovy
// =============================================================================
// Furious Ducks CI/CD - Blue/Green Deployment Helper
//
// Performs a zero-downtime Blue/Green deployment with Docker + Traefik:
//   1. Pull the new image
//   2. Start the GREEN container alongside the running BLUE
//   3. Wait for GREEN to pass its health check
//   4. Switch Traefik routing to GREEN
//   5. (PROD only) Monitor for a configurable period
//   6. Stop the old BLUE container
//   7. Rename GREEN to become the new BLUE
//
// Usage:
//   deployBlueGreen(
//       projectName   : 'my-app',
//       environment   : 'prod',
//       imageTag      : 'v1.2.3',
//       domain        : 'my-app.wk-archi-o24a-4-5-g1.fr',
//       port          : 3000,
//       registry      : 'registry.wk-archi-o24a-4-5-g1.fr',
//       traefikNetwork: 'workflow-network',
//       monitorMinutes: 15
//   )
// =============================================================================

def call(Map config) {

    // -------------------------------------------------------------------------
    // Validate required parameters
    // -------------------------------------------------------------------------
    def required = ['projectName', 'environment', 'imageTag', 'domain', 'port']
    required.each { key ->
        if (!config[key]) {
            error("deployBlueGreen: missing required parameter '${key}'")
        }
    }

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------
    def projectName    = config.projectName
    def environment    = config.environment
    def imageTag       = config.imageTag
    def domain         = config.domain
    def port           = config.port
    def registry       = config.registry       ?: 'registry.wk-archi-o24a-4-5-g1.fr'
    def harborProject  = config.harborProject  ?: 'furious-ducks'
    def traefikNetwork = config.traefikNetwork ?: 'workflow-network'
    def monitorMinutes = config.monitorMinutes ?: (environment == 'prod' ? 15 : 0)
    def healthPath     = config.healthPath     ?: '/health'
    def healthTimeout  = config.healthTimeout  ?: 60   // seconds
    def healthInterval = config.healthInterval ?: 2    // seconds

    def fullImage      = "${registry}/${harborProject}/${projectName}:${imageTag}"
    def blueContainer  = "${projectName}-${environment}-blue"
    def greenContainer = "${projectName}-${environment}-green"
    def previousTag    = ''
    def routerName     = "${projectName}-${environment}"
    // GREEN routes through its own higher-priority router on the same Host rule,
    // so the switch is a real switchover rather than a load-balanced 50/50 split
    // between the two versions (see "B/G - Switch Traffic").
    def greenRouter    = "${routerName}-green"

    echo "=== Blue/Green Deployment ==="
    echo "Project    : ${projectName}"
    echo "Environment: ${environment}"
    echo "Image      : ${fullImage}"
    echo "Domain     : ${domain}"
    echo "Port       : ${port}"
    echo "Monitor    : ${monitorMinutes} minutes"
    echo "=============================="

    // -------------------------------------------------------------------------
    // Step 1: Pull the new image
    // -------------------------------------------------------------------------
    stage("B/G - Pull Image") {
        sh """
            echo "\${HARBOR_CREDENTIALS_PSW}" | docker login ${registry} \
                -u "\${HARBOR_CREDENTIALS_USR}" --password-stdin
            docker pull ${fullImage}
            docker logout ${registry}
        """
    }

    // -------------------------------------------------------------------------
    // Step 2: Start GREEN container with health check
    // -------------------------------------------------------------------------
    stage("B/G - Start GREEN") {
        // Remove any stale green container from a previous attempt
        sh "docker stop ${greenContainer} 2>/dev/null || true"
        sh "docker rm   ${greenContainer} 2>/dev/null || true"

        sh """
            docker run -d \
                --name ${greenContainer} \
                --restart unless-stopped \
                --network ${traefikNetwork} \
                -e NODE_ENV=production \
                -e APP_ENV=${environment} \
                -e APP_VERSION=${imageTag} \
                -e DEPLOYMENT_COLOR=green \
                ${fullImage}
        """

        echo "GREEN container started: ${greenContainer}"
    }

    // -------------------------------------------------------------------------
    // Step 3: Wait for GREEN health check to pass
    // -------------------------------------------------------------------------
    stage("B/G - Health Check") {
        def maxAttempts = (healthTimeout / healthInterval).intValue()

        sh """
            echo "Waiting for GREEN container health check (max ${healthTimeout}s)..."
            for i in \$(seq 1 ${maxAttempts}); do
                if docker run --rm --network ${traefikNetwork} curlimages/curl:latest \
                    -sf "http://${greenContainer}:${port}${healthPath}" > /dev/null 2>&1; then
                    echo "GREEN container is healthy!"
                    exit 0
                fi
                echo "  Attempt \$i/${maxAttempts}: not healthy yet"

                sleep ${healthInterval}
            done

            echo "ERROR: GREEN container did not become healthy within ${healthTimeout}s"
            docker logs --tail 100 ${greenContainer}
            exit 1
        """
    }

    // -------------------------------------------------------------------------
    // Step 4: Switch Traefik routing to GREEN
    // -------------------------------------------------------------------------
    stage("B/G - Switch Traffic") {
        echo "Switching Traefik routing from BLUE to GREEN..."

        // Docker cannot add labels to a running container, so GREEN is recreated
        // from the same registry image with its Traefik labels attached. It is
        // recreated from ${fullImage}, NOT from a `docker commit` of the running
        // container: committing produced a host-local image, so production ended
        // up running something that no longer matched any tag in Harbor and that
        // rollback.sh could not pull back.
        //
        // GREEN gets its OWN router name, carrying a higher priority than BLUE's
        // on the same Host rule. Reusing BLUE's router name (as this stage used
        // to do) makes Traefik see two servers behind a single service and
        // round-robin between the old and the new version - half of production
        // traffic would reach an unvalidated GREEN for the entire monitoring
        // window, which is the opposite of the "validate before switching"
        // guarantee. With a distinct higher-priority router, Traefik sends
        // everything to GREEN while BLUE stays up untouched, so simply removing
        // GREEN falls straight back to BLUE.
        sh """
            docker stop ${greenContainer}
            docker rm   ${greenContainer}

            docker run -d \
                --name ${greenContainer} \
                --restart unless-stopped \
                --network ${traefikNetwork} \
                --label "traefik.enable=true" \
                --label "traefik.http.routers.${greenRouter}.rule=Host(\\`${domain}\\`)" \
                --label "traefik.http.routers.${greenRouter}.priority=100" \
                --label "traefik.http.routers.${greenRouter}.entrypoints=websecure" \
                --label "traefik.http.routers.${greenRouter}.tls.certresolver=letsencrypt" \
                --label "traefik.http.services.${greenRouter}.loadbalancer.server.port=${port}" \
                --label "traefik.http.routers.${greenRouter}.middlewares=secure-headers@file" \
                --label "deploy.tag=${imageTag}" \
                -e NODE_ENV=production \
                -e APP_ENV=${environment} \
                -e APP_VERSION=${imageTag} \
                -e DEPLOYMENT_COLOR=green \
                ${fullImage}
        """

        // Re-check health on the labelled container: it is the one now taking
        // real traffic, and it is a different container from the one validated
        // at step 3. On failure, dropping GREEN restores BLUE immediately.
        int switchExit = sh(
            script: """
                for i in \$(seq 1 ${(healthTimeout / healthInterval).intValue()}); do
                    if docker run --rm --network ${traefikNetwork} curlimages/curl:latest \
                        -sf "http://${greenContainer}:${port}${healthPath}" > /dev/null 2>&1; then
                        exit 0
                    fi
                    sleep ${healthInterval}
                done
                exit 1
            """,
            returnStatus: true
        )
        if (switchExit != 0) {
            sh """
                docker logs --tail 100 ${greenContainer} || true
                docker rm -f ${greenContainer} 2>/dev/null || true
            """
            error("GREEN failed its health check after the traffic switch - " +
                "removed, traffic is back on BLUE (${blueContainer})")
        }

        echo "Traffic is now routed to GREEN (${greenContainer})"
    }

    // -------------------------------------------------------------------------
    // Step 5: Monitor for stability (PROD only)
    // -------------------------------------------------------------------------
    if (monitorMinutes > 0) {
        stage("B/G - Monitor (${monitorMinutes}m)") {
            echo "Monitoring GREEN for ${monitorMinutes} minutes..."

            def monitorSeconds = monitorMinutes * 60
            def checkInterval  = 30  // check every 30 seconds
            def checks         = (monitorSeconds / checkInterval).intValue()

            try {
                sh """
                    FAILURES=0
                    MAX_FAILURES=3

                    for i in \$(seq 1 ${checks}); do
                        # Check HTTP response from an external curl sidecar - the
                        # target image is not guaranteed to ship curl itself
                        HTTP_CODE=\$(docker run --rm --network ${traefikNetwork} curlimages/curl:latest \
                            -s -o /dev/null -w '%{http_code}' "http://${greenContainer}:${port}${healthPath}" 2>/dev/null || echo "000")

                        ELAPSED=\$(( i * ${checkInterval} ))
                        echo "  [\${ELAPSED}/${monitorSeconds}s] http=\${HTTP_CODE}"

                        if [ "\$HTTP_CODE" != "200" ]; then
                            FAILURES=\$((FAILURES + 1))
                            echo "  WARNING: Check failed (\${FAILURES}/\${MAX_FAILURES})"

                            if [ "\$FAILURES" -ge "\$MAX_FAILURES" ]; then
                                echo "ERROR: Too many failures during monitoring - initiating rollback"
                                exit 1
                            fi
                        else
                            # Reset failure counter on success
                            FAILURES=0
                        fi

                        sleep ${checkInterval}
                    done

                    echo "Monitoring complete - GREEN is stable"
                """
            } catch (err) {
                // BLUE was never stopped or relabeled during the traffic switch,
                // so it is still live and serving traffic: removing the failing
                // GREEN is enough to restore full service on BLUE.
                echo "GREEN failed monitoring - rolling back to BLUE (${blueContainer})"
                sh """
                    docker stop ${greenContainer} 2>/dev/null || true
                    docker rm   ${greenContainer} 2>/dev/null || true
                """
                error("Blue/Green deployment for ${projectName} (${environment}) failed monitoring and was rolled back to BLUE (${blueContainer})")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Step 6: Pause old BLUE container as a rollback standby
    // -------------------------------------------------------------------------
    // Kept stopped (not removed) for one generation, so rollback.sh can
    // restart it directly instead of pulling the image again from Harbor.
    stage("B/G - Pause BLUE") {
        echo "Pausing old BLUE container as rollback standby..."

        // Capture the tag currently running on BLUE before touching it, so the
        // new BLUE can be labeled with it (rollback.sh reads this to auto-detect
        // the tag to roll back to, without the operator having to know it).
        previousTag = sh(
            script: "docker inspect --format='{{index .Config.Labels \"deploy.tag\"}}' ${blueContainer} 2>/dev/null || true",
            returnStdout: true
        ).trim()
        if (previousTag == '<no value>') {
            previousTag = ''
        }

        sh """
            # Drop the standby from two releases ago - we only keep one generation back
            docker rm -f ${blueContainer}-previous 2>/dev/null || true

            docker stop ${blueContainer} 2>/dev/null || true
            docker rename ${blueContainer} ${blueContainer}-previous 2>/dev/null || true
            echo "BLUE container paused as ${blueContainer}-previous"
        """
    }

    // -------------------------------------------------------------------------
    // Step 7: Rename GREEN to become new BLUE
    // -------------------------------------------------------------------------
    stage("B/G - Promote GREEN") {
        echo "Promoting GREEN to BLUE..."

        // Docker cannot rename the router a container declares, so GREEN cannot
        // simply be renamed to BLUE: the next deployment would then create a new
        // GREEN declaring the same "-green" router as the promoted one. The new
        // BLUE is therefore started from the registry image with the canonical
        // router, and only once it is healthy is GREEN removed. GREEN keeps the
        // higher priority until that moment, so traffic never lands on a BLUE
        // that has not answered a health check - previously GREEN was torn down
        // first, leaving the domain with no backend at all while BLUE booted.
        sh """
            docker run -d \
                --name ${blueContainer} \
                --restart unless-stopped \
                --network ${traefikNetwork} \
                --label "traefik.enable=true" \
                --label "traefik.http.routers.${routerName}.rule=Host(\\`${domain}\\`)" \
                --label "traefik.http.routers.${routerName}.entrypoints=websecure" \
                --label "traefik.http.routers.${routerName}.tls.certresolver=letsencrypt" \
                --label "traefik.http.services.${routerName}.loadbalancer.server.port=${port}" \
                --label "traefik.http.routers.${routerName}.middlewares=secure-headers@file" \
                --label "deploy.tag=${imageTag}" \
                --label "deploy.previous-tag=${previousTag}" \
                -e NODE_ENV=production \
                -e APP_ENV=${environment} \
                -e APP_VERSION=${imageTag} \
                -e DEPLOYMENT_COLOR=blue \
                ${fullImage}
        """

        int promoteExit = sh(
            script: """
                for i in \$(seq 1 15); do
                    if docker run --rm --network ${traefikNetwork} curlimages/curl:latest \
                        -sf "http://${blueContainer}:${port}${healthPath}" > /dev/null 2>&1; then
                        exit 0
                    fi
                    sleep 2
                done
                exit 1
            """,
            returnStatus: true
        )
        if (promoteExit != 0) {
            sh """
                docker logs --tail 100 ${blueContainer} || true
                docker rm -f ${blueContainer} 2>/dev/null || true
            """
            error("New BLUE failed its health check - removed, traffic stays on " +
                "GREEN (${greenContainer}). Investigate before re-running the deployment.")
        }

        // BLUE is healthy and serving on the canonical router: retiring GREEN
        // now hands all traffic over to it.
        sh "docker rm -f ${greenContainer} 2>/dev/null || true"
        echo 'New BLUE container is healthy - deployment complete!'
    }

    echo "=== Blue/Green Deployment Complete ==="
    echo "Active container: ${blueContainer}"
    echo "Image: ${fullImage}"
    echo "Domain: https://${domain}"
}

// =============================================================================
// Rollback helper - can be called independently
// =============================================================================
def rollback(Map config) {
    def projectName    = config.projectName
    def environment    = config.environment
    def previousTag    = config.previousTag
    def domain         = config.domain
    def port           = config.port       ?: 3000
    def registry       = config.registry   ?: 'registry.wk-archi-o24a-4-5-g1.fr'
    def harborProject  = config.harborProject ?: 'furious-ducks'
    def traefikNetwork = config.traefikNetwork ?: 'workflow-network'

    def blueContainer  = "${projectName}-${environment}-blue"
    def greenContainer = "${projectName}-${environment}-green"
    def routerName     = "${projectName}-${environment}"
    def fullImage      = "${registry}/${harborProject}/${projectName}:${previousTag}"

    echo "=== ROLLBACK: Reverting ${projectName} ${environment} to ${previousTag} ==="

    sh """
        # Stop the current (failed) containers
        docker stop ${greenContainer} 2>/dev/null || true
        docker rm   ${greenContainer} 2>/dev/null || true
        docker stop ${blueContainer}  2>/dev/null || true
        docker rm   ${blueContainer}  2>/dev/null || true

        # Pull and run the previous known-good version
        echo "\${HARBOR_CREDENTIALS_PSW}" | docker login ${registry} \
            -u "\${HARBOR_CREDENTIALS_USR}" --password-stdin
        docker pull ${fullImage}
        docker logout ${registry}

        docker run -d \
            --name ${blueContainer} \
            --restart unless-stopped \
            --network ${traefikNetwork} \
            --label "traefik.enable=true" \
            --label "traefik.http.routers.${routerName}.rule=Host(\\`${domain}\\`)" \
            --label "traefik.http.routers.${routerName}.entrypoints=websecure" \
            --label "traefik.http.routers.${routerName}.tls.certresolver=letsencrypt" \
            --label "traefik.http.services.${routerName}.loadbalancer.server.port=${port}" \
            --label "traefik.http.routers.${routerName}.middlewares=secure-headers@file" \
            -e NODE_ENV=production \
            -e APP_ENV=${environment} \
            -e APP_VERSION=${previousTag} \
            -e DEPLOYMENT_COLOR=blue \
            ${fullImage}

        echo "Rollback complete - running ${previousTag}"
    """
}
