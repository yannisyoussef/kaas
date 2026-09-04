// The full-pipeline test module. It contains NO production source and never will: it is the one place that
// depends on both the control plane and the runner, so anything written here would be code that violates the
// dependency boundary the other two modules enforce.
plugins {
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports { mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.1") }
}

dependencies {
    testImplementation(project(":apps:api"))
    testImplementation(project(":services:runner"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.postgresql:postgresql")

    // The container-runtime client, declared explicitly rather than leaned on transitively. :services:runner
    // takes it as `implementation`, so it is deliberately not on this module's compile classpath by
    // inheritance — and depending on another module's implementation details by accident is how a boundary
    // erodes without anybody deciding to erode it.
    testImplementation("com.github.docker-java:docker-java-core:3.4.1")
    testImplementation("com.github.docker-java:docker-java-transport-httpclient5:3.4.1")
    testImplementation("org.assertj:assertj-core:3.27.3")
    // Minting the tenant and service tokens this test authenticates with. The control plane verifies real
    // signatures on the real filter chains, so the test has to produce real ones.
    testImplementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    testImplementation("com.nimbusds:nimbus-jose-jwt")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// This module has no main source set, so there is nothing to compile or jar.
sourceSets { named("main") { java.setSrcDirs(emptyList<String>()) } }

tasks.withType<Test>().configureEach {
    // Real containers on both sides — a PostgreSQL for the control plane and a sandbox for the workload.
    // Running these in parallel would make one test's resource observations depend on another's timing.
    maxParallelForks = 1

    // The probe image and its build context are the source of every behavioural claim this module makes, and
    // they live in another module's directory rather than in a source set — so Gradle would not consider them
    // inputs. Without this, editing the probe leaves this task UP-TO-DATE and the pipeline test silently does
    // not re-run: a change to the only thing that actually executes would be a guaranteed false pass.
    inputs.dir(rootProject.layout.projectDirectory.dir("services/runner/src/main/docker"))
        .withPropertyName("probeBuildContext")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The migrations are the other half of what this test exercises, and they are resources of a different
    // module. Same reasoning: a schema change that broke the pipeline must not leave this task cached green.
    inputs.dir(rootProject.layout.projectDirectory.dir("apps/api/src/main/resources/db/migration"))
        .withPropertyName("migrations")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
