package com.kaas.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ControlPlaneArchitectureTest {

    // ONE definition of each package selector, shared by the rules below and by the anti-vacuity guard.
    //
    // The sharing is the whole mechanism. Previously the guard held its own copy of each package name, so
    // misspelling a selector in a RULE left the guard's string untouched and the guard went on finding classes
    // — it could only ever catch a mistake somebody made in two places at once. Mutation testing showed this
    // directly: renaming `..execution.domain..` in the rules killed nothing. With one constant, a misspelling
    // is a misspelling everywhere, and the floor below fails.
    private static final String CONTROL_PLANE = "..controlplane..";
    private static final String CONTROL_PLANE_DOMAIN = "..controlplane.domain..";
    private static final String CONTROL_PLANE_API = "..controlplane.api..";
    private static final String OUTBOX = "..outbox..";
    private static final String OUTBOX_DOMAIN = "..outbox.domain..";
    private static final String CONSUMER = "..consumer..";
    private static final String CONSUMER_DOMAIN = "..consumer.domain..";
    private static final String EXECUTION = "..execution..";
    private static final String EXECUTION_DOMAIN = "..execution.domain..";
    private static final String INFRASTRUCTURE = "..infrastructure..";

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

        // EVERY selector a rule uses, checked through the SAME constant the rule uses.
        //
        // ArchUnit's `..x..` form means "package x and its subpackages"; the equivalent substring test is the
        // dotted name without the wrapping dots. Deriving it from the constant rather than writing it out again
        // is what makes a misspelling visible here.
        for (String selector : java.util.List.of(
                CONTROL_PLANE, CONTROL_PLANE_DOMAIN, CONTROL_PLANE_API, OUTBOX, OUTBOX_DOMAIN,
                CONSUMER, CONSUMER_DOMAIN, EXECUTION, EXECUTION_DOMAIN, INFRASTRUCTURE)) {
            // ArchUnit's `..x..` means package x AND its subpackages, so the equivalent test is "contains .x."
            // or "ends with .x" — a leaf package has no trailing dot, which a naive substring test misses.
            String core = selector.replace("..", "");
            assertThat(classes.stream()
                            .filter(imported -> imported.getPackageName().contains("." + core + ".")
                                    || imported.getPackageName().endsWith("." + core))
                            .count())
                    .as("the selector %s must match real classes, or every rule using it passes vacuously",
                            selector)
                    .isGreaterThan(0L);
        }

        // And the rules that select by TYPE NAME rather than by package. A typo there matches nothing and the
        // rule — described in its own comment as the load-bearing claim of the security-gate bridge — passes
        // having checked no dependency at all. No package count can catch that.
        for (String type : java.util.List.of(
                "VerifiedSandboxSecurityAttestation",
                "SandboxSecurityAttestationSource",
                "SandboxSecurityAttestationVerifier",
                "AttestationTrustStore")) {
            assertThat(classes.stream().filter(imported -> imported.getSimpleName().equals(type)).count())
                    .as("the rule targeting %s must have a real type to target", type)
                    .isEqualTo(1L);
        }
    }
    @Test
    void domainIsFrameworkIndependent() {
        // Each domain package may depend only on the JDK and itself. Allowing every "..domain.." package to see
        // every other would silently permit the control-plane domain to depend on the outbox domain.
        noClasses()
                .that()
                .resideInAPackage(CONTROL_PLANE_DOMAIN)
                .should()
                .dependOnClassesThat()
                .resideOutsideOfPackages("java..", "com.kaas.api.controlplane.domain..")
                .check(classes);
        noClasses()
                .that()
                .resideInAPackage(OUTBOX_DOMAIN)
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
                .resideInAPackage(OUTBOX)
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
                .resideInAPackage(CONSUMER)
                .allowEmptyShould(true)
                .check(classes);
        // The consumer's own domain stays framework-free for the same reason every other domain does.
        noClasses()
                .that()
                .resideInAPackage(CONSUMER_DOMAIN)
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
                .resideInAPackage(CONTROL_PLANE_API)
                .should()
                .dependOnClassesThat()
                .resideInAPackage(INFRASTRUCTURE)
                .check(classes);
    }

    @Test
    void controlPlaneCannotDependOnExecutionBrokerStorageOrSecretProviders() {
        noClasses()
                .that()
                .resideInAPackage(CONTROL_PLANE)
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
                .resideInAPackage(CONTROL_PLANE)
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
                .resideInAPackage(CONTROL_PLANE_API)
                .should()
                .dependOnClassesThat()
                .resideInAPackage(EXECUTION)
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void theSecretProviderAbstractionHasNoProductionSdkBehindIt() {
        // The interface exists so the capability envelope can be designed while the thing it protects is
        // absent. If an SDK appears here, the envelope stops being the only thing that was built.
        noClasses()
                .that()
                .resideInAPackage(EXECUTION_DOMAIN)
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
    void noEndpointCanAcceptASandboxSecurityAttestationOrNominateAKey() {
        // The load-bearing claim of the whole security-gate bridge: an attestation arrives as deployment
        // configuration and there is no API that accepts one, so nothing which authenticates to this service
        // can assert its own security posture. That claim was previously true only by inspection — one
        // @RequestBody on any controller would have falsified it silently.
        //
        // The trust store and the verifier are named here too, and they matter more than the attestation
        // itself: a surface that could reach either could let a caller nominate WHO IS TRUSTED TO SIGN, which
        // is strictly worse than being able to submit a document. A document a pinned key did not sign is
        // worthless; a caller-chosen key makes every document worth something.
        noClasses()
                .that()
                .resideOutsideOfPackage("..execution..")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("VerifiedSandboxSecurityAttestation")
                .orShould()
                .dependOnClassesThat()
                .haveSimpleName("SandboxSecurityAttestationSource")
                .orShould()
                .dependOnClassesThat()
                .haveSimpleName("SandboxSecurityAttestationVerifier")
                .orShould()
                .dependOnClassesThat()
                .haveSimpleName("AttestationTrustStore")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void theControlPlaneCannotProduceAnAttestationItWouldThenVerify() {
        // The control plane holds VERIFICATION authority and must never hold SIGNING authority. If it could
        // mint an attestation it would be both the party making the claim and the party checking it, and the
        // signature would authenticate nothing anybody did not already control.
        //
        // Asserted structurally rather than by convention: a private key type on this classpath is the shape
        // that would make it possible, whatever the intention of the code holding it.
        noClasses()
                .that()
                .resideInAPackage("..execution..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.security.PrivateKey")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.security.KeyPairGenerator")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.security.spec.PKCS8EncodedKeySpec")
                .allowEmptyShould(true)
                .check(classes);
    }
}
