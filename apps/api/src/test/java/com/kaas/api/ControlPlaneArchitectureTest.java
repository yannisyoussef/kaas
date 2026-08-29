package com.kaas.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ControlPlaneArchitectureTest {
    private final com.tngtech.archunit.core.domain.JavaClasses classes =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("com.kaas.api");

    @Test
    void thePackageSelectorsUsedByTheseRulesActuallyMatchClasses() {
        // Every rule below uses allowEmptyShould(true), so a mistyped package would make them pass silently.
        assertThat(classes.stream().filter(imported -> imported.getPackageName().contains(".controlplane.")).count())
                .isGreaterThan(20L);
        assertThat(classes.stream()
                        .filter(imported -> imported.getPackageName().contains(".controlplane.domain"))
                        .count())
                .isGreaterThan(10L);
        assertThat(classes.stream()
                        .filter(imported -> imported.getPackageName().contains(".outbox.domain"))
                        .count())
                .isGreaterThan(5L);
        assertThat(classes.stream()
                        .filter(imported -> imported.getPackageName().contains(".consumer."))
                        .count())
                .isGreaterThan(5L);
        // The execution rules were outside this guard entirely: rewriting all four selectors to a misspelled
        // package left the whole class green, including this test. A rule that matches no class passes
        // vacuously, and allowEmptyShould(true) is what makes that silent.
        assertThat(classes.stream()
                        .filter(imported -> imported.getPackageName().contains(".execution."))
                        .count())
                .as("the execution package selectors must match real classes")
                .isGreaterThan(10L);
        assertThat(classes.stream()
                        .filter(imported -> imported.getPackageName().contains(".internal"))
                        .count())
                .as("the internal package selectors must match real classes")
                .isGreaterThan(0L);
    }

    @Test
    void domainIsFrameworkIndependent() {
        // Each domain package may depend only on the JDK and itself. Allowing every "..domain.." package to see
        // every other would silently permit the control-plane domain to depend on the outbox domain.
        noClasses()
                .that()
                .resideInAPackage("..controlplane.domain..")
                .should()
                .dependOnClassesThat()
                .resideOutsideOfPackages("java..", "com.kaas.api.controlplane.domain..")
                .check(classes);
        noClasses()
                .that()
                .resideInAPackage("..outbox.domain..")
                .should()
                .dependOnClassesThat()
                .resideOutsideOfPackages("java..", "com.kaas.api.outbox.domain..")
                .check(classes);
    }

    @Test
    void onlyTheControlPlanesPersistenceAdapterMayNameTheDeliveryContext() {
        // The control plane writes outbox rows directly — that is the transactional outbox pattern, and it is
        // deliberate. What must not spread is knowledge of the delivery context into the lifecycle model or its
        // use cases, where an outbox concept would start shaping decisions that belong to the run.
        //
        // JdbcRunTerminationRepository imports TerminalDisposition rather than repeating its constants as a third
        // set of string literals, and this rule is what confines that to the adapter. Without it, "no compile-time
        // dependency on the delivery context" would be a convention nothing protects.
        noClasses()
                .that()
                .resideInAnyPackage("..controlplane.domain..", "..controlplane.application..", "..controlplane.api..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..outbox..")
                .check(classes);
    }

    @Test
    void theConsumerIsAnInboundAdapterAndOwnsNoLifecycleDecisions() {
        // The consumer package may call the control plane's use cases — that is what an inbound adapter does —
        // but nothing in the control plane may depend on it. A dependency in that direction would mean a broker
        // concept had reached the lifecycle model, and the lifecycle must be decidable without a broker at all.
        noClasses()
                .that()
                .resideInAnyPackage("..controlplane..", "..outbox..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..consumer..")
                .allowEmptyShould(true)
                .check(classes);
        // The consumer's own domain stays framework-free for the same reason every other domain does.
        noClasses()
                .that()
                .resideInAPackage("..consumer.domain..")
                .should()
                .dependOnClassesThat()
                .resideOutsideOfPackages("java..", "com.kaas.api.consumer.domain..")
                .check(classes);
        // And the broker client stays in the consumer's infrastructure, exactly as it does for the relay.
        noClasses()
                .that()
                .resideInAnyPackage("..consumer.domain..", "..consumer.application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.amqp..", "com.rabbitmq..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void claimingGrantsNoExecutionAuthority() {
        // The whole point of stopping at CLAIMED is that ownership and permission to execute are different
        // things. If any of these ever appear on the claim path, that distinction has quietly collapsed.
        noClasses()
                .that()
                .resideInAnyPackage("..controlplane..", "..consumer..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.kaas.runner..",
                        "com.intuit.karate..",
                        "io.minio..",
                        "com.github.dockerjava..",
                        "org.springframework.vault..",
                        "software.amazon.awssdk.services.secretsmanager..",
                        "com.azure.security.keyvault..",
                        "com.google.cloud.secretmanager..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void apiDoesNotDependOnPersistenceAdapters() {
        noClasses()
                .that()
                .resideInAPackage("..controlplane.api..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..infrastructure..")
                .check(classes);
    }

    @Test
    void controlPlaneCannotDependOnExecutionBrokerStorageOrSecretProviders() {
        noClasses()
                .that()
                .resideInAPackage("..controlplane..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.kaas.runner..",
                        "com.intuit.karate..",
                        "org.springframework.amqp..",
                        "com.rabbitmq..",
                        "io.minio..",
                        "com.github.dockerjava..",
                        "org.springframework.vault..",
                        "software.amazon.awssdk.services.secretsmanager..",
                        "com.azure.security.keyvault..",
                        "com.google.cloud.secretmanager..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void controlPlaneCannotLaunchProcessesOrScheduleExecution() {
        noClasses()
                .that()
                .resideInAPackage("..controlplane..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.lang.ProcessBuilder")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.lang.Runtime")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.scheduling.annotation.Scheduled")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.scheduling.TaskScheduler")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.util.concurrent.ScheduledExecutorService")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void executionAuthorizationCannotLaunchAnythingOrReachAContainerRuntime() {
        // The whole point of this slice is that a command can be produced and has nowhere to go. A dependency
        // on a process API, a container client, or Karate would make that a matter of nobody having called it
        // yet rather than of there being nothing to call.
        noClasses()
                .that()
                .resideInAnyPackage("..execution..", "..internal..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.github.dockerjava..",
                        "com.kaas.runner..",
                        "com.intuit.karate..",
                        "io.minio..",
                        "org.springframework.vault..",
                        "software.amazon.awssdk.services.secretsmanager..",
                        "com.azure.security.keyvault..",
                        "com.google.cloud.secretmanager..")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.lang.ProcessBuilder")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.lang.Runtime")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void issuingACommandCannotPublishItToABroker() {
        // No AMQP anywhere in the execution package. A command that could be published is a command that will
        // be published, and delivery belongs to a slice that has somewhere to deliver it to.
        noClasses()
                .that()
                .resideInAnyPackage("..execution..", "..internal..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.amqp..", "com.rabbitmq..", "..outbox..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void noUserFacingControllerTouchesExecutionAuthority() {
        // Execution authorization is not a tenant API. The public controllers must not be able to reach it even
        // by accident, because an endpoint that authenticates a tenant and issues platform authority is the
        // confused deputy this design exists to prevent.
        noClasses()
                .that()
                .resideInAPackage("..controlplane.api..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..execution..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void theSecretProviderAbstractionHasNoProductionSdkBehindIt() {
        // The interface exists so the capability envelope can be designed while the thing it protects is
        // absent. If an SDK appears here, the envelope stops being the only thing that was built.
        noClasses()
                .that()
                .resideInAPackage("..execution.domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.vault..",
                        "software.amazon.awssdk..",
                        "com.azure..",
                        "com.google.cloud..",
                        "com.bettercloud..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void noEndpointCanAcceptASandboxSecurityAttestation() {
        // The load-bearing claim of the whole security-gate bridge: an attestation arrives as deployment
        // configuration and there is no API that accepts one, so nothing which authenticates to this service can
        // assert its own security posture. That claim was previously true only by inspection — one
        // @RequestBody on any controller would have falsified it silently.
        noClasses()
                .that()
                .resideOutsideOfPackage("..execution..")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("SandboxSecurityAttestation")
                .orShould()
                .dependOnClassesThat()
                .haveSimpleName("SandboxSecurityAttestationSource")
                .allowEmptyShould(true)
                .check(classes);
    }
}
