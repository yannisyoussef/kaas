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
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
    mockitoAgent("org.mockito:mockito-core") { isTransitive = false }
}

tasks.withType<Test>().configureEach {
    maxParallelForks = 1
    jvmArgs("-javaagent:${mockitoAgent.asPath}")
}

val verifyNoExecutionDependencies = tasks.register("verifyNoExecutionDependencies") {
    group = "verification"
    description = "Fails if the control plane acquires Karate or runner execution dependencies."
    doLast {
        val forbidden = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
            .map { "${it.moduleVersion.id.group}:${it.name}" }
            .filter { it.startsWith("com.intuit.karate:") || it.contains("kaas:runner") }
        check(forbidden.isEmpty()) { "Execution dependencies are forbidden in apps/api: $forbidden" }
    }
}

tasks.named("check") {
    dependsOn(verifyNoExecutionDependencies)
}
