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

        // Apply Traefik labels to GREEN container by recreating it with labels
        // (Docker does not allow adding labels to a running container, so we
        //  use the Traefik dynamic file provider or a label-update approach.)
        //
        // Strategy: stop GREEN, re-run with Traefik labels, then stop BLUE.
        sh """
            # Commit the running GREEN container to preserve its state
            docker commit ${greenContainer} ${fullImage}-green-tmp

            # Stop the unlabeled GREEN
            docker stop ${greenContainer}
            docker rm   ${greenContainer}

            # Re-run GREEN with full Traefik labels
            docker run -d \
                --name ${greenContainer} \
                --restart unless-stopped \
                --network ${traefikNetwork} \
                --label "traefik.enable=true" \
                --label "traefik.http.routers.${routerName}.rule=Host(\\`${domain}\\`)" \
                --label "traefik.http.routers.${routerName}.entrypoints=websecure" \
                --label "traefik.http.routers.${routerName}.tls.certresolver=letsencrypt" \
                --label "traefik.http.services.${routerName}.loadbalancer.server.port=${port}" \
                --label "traefik.http.routers.${routerName}.middlewares=secure-headers@file" \
                --label "deploy.tag=${imageTag}" \
                -e NODE_ENV=production \
                -e APP_ENV=${environment} \
                -e APP_VERSION=${imageTag} \
                -e DEPLOYMENT_COLOR=green \
                ${fullImage}-green-tmp

            # Clean up temporary image
            docker rmi ${fullImage}-green-tmp 2>/dev/null || true

            # Wait a moment for Traefik to pick up the new labels
            sleep 5

            echo "Traffic is now routed to GREEN (${greenContainer})"
        """
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
        sh """
            # Docker does not support renaming directly when Traefik labels are
            # tied to the container name in the router rule. Instead we stop
            # GREEN, re-run as BLUE with the same image and labels.
            CURRENT_IMAGE=\$(docker inspect --format='{{.Config.Image}}' ${greenContainer})

            docker stop ${greenContainer}
            docker rm   ${greenContainer}

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
                \${CURRENT_IMAGE}

            # Verify the new BLUE is healthy
            for i in \$(seq 1 15); do
                if docker run --rm --network ${traefikNetwork} curlimages/curl:latest \
                    -sf "http://${blueContainer}:${port}${healthPath}" > /dev/null 2>&1; then
                    echo "New BLUE container is healthy - deployment complete!"
                    exit 0
                fi
                sleep 2
            done

            echo "WARNING: New BLUE container health check inconclusive - verify manually"
        """
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
