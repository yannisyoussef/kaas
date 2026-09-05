plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

val mockitoAgent = configurations.create("mockitoAgent")

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-rabbitmq")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
    mockitoAgent("org.mockito:mockito-core") { isTransitive = false }
}

tasks.withType<Test>().configureEach {
    maxParallelForks = 1
    jvmArgs("-javaagent:${mockitoAgent.asPath}")
    // The shared mandatory-control contract is read at runtime by MandatoryControlContractTest, so Gradle does
    // not know it is an input unless told. Without this the test is UP-TO-DATE and skipped in exactly the case
    // it exists for — a change to that file — and CI restores the Gradle cache, so a pull request touching only
    // the contract reproduces it. The assertions were sound; nothing was running them.
    inputs.file(rootProject.file("packages/api-contracts/mandatory-sandbox-controls.json"))
        .withPropertyName("mandatorySandboxControls")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The signing contract and its vectors, for the same reason and with a sharper edge: these files ARE the
    // agreement between the producer and the verifier, and a change to one of them changes what a signature
    // means. A vector edited while this task reported UP-TO-DATE would be a contract nobody checked.
    inputs.file(rootProject.file("packages/api-contracts/sandbox-security-attestation-signing.md"))
        .withPropertyName("attestationSigningContract")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("packages/api-contracts/fixtures/sandbox-security-attestation-signing"))
        .withPropertyName("attestationSigningVectors")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

/**
 * Verifies a signed attestation offline, with the same verifier the control plane uses.
 *
 * <p>For an operator checking an artifact before deploying it, and for CI proving that what a producer just
 * wrote is what a real verifier accepts. It grants nothing: it reads a file and prints a category.
 */
val verifySandboxSecurityAttestation = tasks.register<JavaExec>("verifySandboxSecurityAttestation") {
    group = "verification"
    description = "Verifies a signed sandbox security attestation against a pinned trust root."
    mainClass.set("com.kaas.api.execution.application.AttestationVerificationCli")
    classpath = sourceSets["main"].runtimeClasspath
    doFirst {
        systemProperty(
            "kaas.attestation.verify.document",
            providers.gradleProperty("kaasAttestationDocument").get())
        systemProperty(
            "kaas.attestation.verify.trusted-keys",
            providers.gradleProperty("kaasAttestationTrustedKeys").get())
        providers.gradleProperty("kaasAttestationRuntimeSubjects").orNull?.let {
            systemProperty("kaas.attestation.verify.runtime-subjects", it)
        }
        providers.gradleProperty("kaasAttestationProfileVersion").orNull?.let {
            systemProperty("kaas.attestation.verify.profile-version", it)
        }
    }
}

val verifyNoExecutionDependencies = tasks.register("verifyNoExecutionDependencies") {
    group = "verification"
    description = "Fails if the control plane acquires execution, object-store, or secret-provider dependencies on its SHIPPED runtime classpath. RabbitMQ is an intended transport dependency from the outbox relay slice onward; it carries no execution authority. The container-runtime client is confined to services/runner: the process that handles tenant requests must never be able to talk to a container daemon, because a component with daemon access is a component with the host. Scope matters and is stated rather than implied: Testcontainers puts docker-java on this module's TEST classpath, so the guarantee is about what is shipped, not about every JVM this module ever starts."
    doLast {
        val forbidden = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
            .map { "${it.moduleVersion.id.group}:${it.name}" }
            .filter {
                it.startsWith("com.intuit.karate:") ||
                    it.contains("kaas:runner") ||
                    it.startsWith("io.minio:") ||
                    it.startsWith("com.github.docker-java:") ||
                    it.startsWith("org.springframework.vault:") ||
                    it.startsWith("com.bettercloud:") ||
                    it.startsWith("software.amazon.awssdk:secretsmanager") ||
                    it.startsWith("com.amazonaws:aws-java-sdk-secretsmanager") ||
                    it.startsWith("com.azure:azure-security-keyvault") ||
                    it.startsWith("com.google.cloud:google-cloud-secretmanager")
                // No org.testcontainers clause: it is declared testImplementation and can never appear on this
                // configuration, so the check could never fire and read as protection that did not exist.
            }
        check(forbidden.isEmpty()) { "Forbidden control-plane dependencies found in apps/api: $forbidden" }
    }
}

tasks.named("check") {
    dependsOn(verifyNoExecutionDependencies)
}
