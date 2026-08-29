package com.kaas.api.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import jakarta.annotation.PostConstruct;

/**
 * Refuses to start if someone tries to turn on execution before the things that make it safe exist.
 *
 * <p>The flag is not a feature toggle. Execution of user-supplied Karate content requires a source capability
 * model, a secret capability model, an egress policy, and a passing hostile-execution assessment on the
 * deployment's own runtime — none of which exists. A flag that merely gated the code path would let a single
 * configuration change skip all four, which is exactly how a security boundary becomes a comment.
 *
 * <p>So the guard is structural: there is no code behind the flag to enable, and the application refuses to
 * start rather than starting in a state whose name promises something it cannot do. There is deliberately no
 * development bypass — a bypass is the thing an attacker looks for, and a boundary with a documented way
 * around it is not a boundary.
 *
 * <p>{@code @Lazy(false)} is what makes that true rather than merely intended. Nothing injects this bean, so
 * its only enforcement is the callback below, and under {@code spring.main.lazy-initialization=true} the
 * container never instantiated it at all: the application started cleanly with execution enabled. A guard that
 * one documented property switches off is the documented bypass this class says it does not have.
 */
@Configuration(proxyBeanMethods = false)
@Lazy(false)
public class ExecutionCapabilityGuard {
    private final boolean executionEnabled;

    public ExecutionCapabilityGuard(@Value("${kaas.execution.enabled}") boolean executionEnabled) {
        this.executionEnabled = executionEnabled;
    }

    @PostConstruct
    void refuseToPretendExecutionIsAvailable() {
        if (executionEnabled) {
            throw new IllegalStateException(
                    "kaas.execution.enabled is not implemented and cannot be turned on. Executing user content"
                            + " requires source capability issuance, secret capability issuance, an egress policy"
                            + " model, and a passing hostile-execution assessment for this deployment's runtime."
                            + " None of those exists yet, and there is no bypass.");
        }
    }
}
