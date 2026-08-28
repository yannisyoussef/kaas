package com.kaas.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class ControlPlaneArchitectureTest {
    private final com.tngtech.archunit.core.domain.JavaClasses classes =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("com.kaas.api");

    @Test
    void domainIsFrameworkIndependent() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideOutsideOfPackages("java..", "com.kaas.api.controlplane.domain..");
        rule.check(classes);
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
}
