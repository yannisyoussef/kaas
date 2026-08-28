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
