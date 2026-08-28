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
}
