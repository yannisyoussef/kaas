package com.kaas.api.deployment;

import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

/**
 * {@code /actuator/deployment}: the platform base's operational status, for the post-deploy check.
 *
 * <p>Exposed only where the deployment exposes it -- the production profile puts it on the management port,
 * which Operations binds to a private network and never routes through public ingress.
 */
@Component
@Endpoint(id = "deployment")
class DeploymentEndpoint {
    private final DeploymentStatusService status;

    DeploymentEndpoint(DeploymentStatusService status) {
        this.status = status;
    }

    @ReadOperation
    Map<String, Object> deployment() {
        return status.status();
    }
}
