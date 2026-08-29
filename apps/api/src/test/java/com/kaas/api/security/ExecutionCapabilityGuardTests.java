package com.kaas.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The guard that refuses to let execution be switched on.
 *
 * <p>This is the single most-cited security claim in the slice — ADR-022 and the security document both state
 * that the application refuses to start with {@code kaas.execution.enabled=true} and that there is deliberately
 * no bypass — and until now it had no test of any kind. Every other control here was mutation-checked; this one
 * was not checked at all, which meant {@code if (executionEnabled)} could have been {@code if (false)} and
 * nothing in the build would have noticed.
 *
 * <p>The lazy-initialization case is the reason this file is not merely box-ticking. Nothing injects this bean,
 * so its only enforcement is a {@code @PostConstruct} callback, and under
 * {@code spring.main.lazy-initialization=true} the container never instantiated it: the application started
 * cleanly with execution enabled. That is a bypass reachable through one documented Spring property, in a class
 * whose documentation says no bypass exists.
 */
class ExecutionCapabilityGuardTests {

    private final ApplicationContextRunner contexts =
            new ApplicationContextRunner().withConfiguration(UserConfigurations.of(ExecutionCapabilityGuard.class));

    @Test
    void theApplicationStartsWhenExecutionIsNotEnabled() {
        // Without this the refusal tests below would be satisfied by a guard that refused unconditionally.
        contexts.withPropertyValues("kaas.execution.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest(name = "kaas.execution.enabled={0} refuses to start")
    @ValueSource(strings = {"true", "TRUE", "True", "yes", "on", "1"})
    void theApplicationRefusesToStartWhenExecutionIsEnabled(String enabled) {
        // Every spelling Spring's relaxed binding accepts. A guard that caught only the literal "true" would
        // leave the others as a way to start a deployment whose name promises what it cannot do.
        contexts.withPropertyValues("kaas.execution.enabled=" + enabled)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("kaas.execution.enabled is not implemented"));
    }

    @Test
    void theRefusalSurvivesLazyInitialization() {
        // The demonstrated bypass. LazyInitializationBeanFactoryPostProcessor marks every bean definition lazy,
        // nothing injects this one, and so its @PostConstruct never ran: the context started with execution
        // enabled. @Lazy(false) on the guard is what makes the refusal unconditional.
        //
        // The post-processor is registered explicitly rather than requested through
        // spring.main.lazy-initialization, because that property is honoured by SpringApplication and ignored
        // by ApplicationContextRunner. Written the obvious way, this test passed with @Lazy(false) deleted --
        // it was asserting that a context with no lazy initialization refuses, which the other tests already
        // cover. Mutation-checked: removing the annotation now turns it red.
        contexts.withPropertyValues("kaas.execution.enabled=true")
                .withInitializer(context ->
                        context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void theRefusalDoesNotDependOnADefaultBeingSuppliedSomewhereElse() {
        // With the property absent entirely the context must not start silently as though execution were off.
        // A missing value is a misconfiguration, and the safe reading of a misconfiguration in a security
        // control is refusal.
        contexts.run(context -> assertThat(context).hasFailed());
    }
}
