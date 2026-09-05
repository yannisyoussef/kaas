import org.gradle.api.attributes.Usage

// The full-pipeline test module. It contains NO production source and never will: it is the one place that
// depends on both the control plane and the runner, so anything written here would be code that violates the
// dependency boundary the other two modules enforce.
plugins {
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports { mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.1") }
}

/**
 * The egress proxy's image build context, resolved rather than reached across for.
 *
 * <p>Same reasoning as in :services:runner. A path guessed into another project's build directory works until
 * it silently does not: the test can run before that project has produced anything, and the evidence is then
 * an image built from whatever a previous build left behind.
 */
val proxyImageContext: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, "kaas-proxy-image-context")) }
}

dependencies {
    testImplementation(project(":apps:api"))
    testImplementation(project(":services:runner"))
    // The proxy's own code, so the end-to-end test drives the REAL ControlPlaneAuthorizer against the REAL
    // control plane rather than a second implementation of the same exchange. A stub on this side would
    // agree with itself about the wire format, which is the one thing this test exists to check.
    testImplementation(project(":services:egress-proxy"))
    // The programmable authoritative DNS server, shared with the proxy's own suite rather than duplicated.
    // The end-to-end allowlist run needs a resolver whose answers this test controls, and a second
    // implementation of one would be a second chance to answer differently from the server the proxy is
    // actually tested against.
    testImplementation(testFixtures(project(":services:egress-proxy")))
    proxyImageContext(project(":services:egress-proxy"))

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

/**
 * One SLF4J binding on this module's test classpath, enforced for the whole configuration rather than per
 * dependency.
 *
 * <p>The egress proxy ships its own binding because it runs as a standalone process in a container with no
 * framework to provide one. This module runs a Spring Boot context, which brings Logback, and two bindings on
 * one classpath is a startup failure rather than a warning: every test here fails to load its context.
 *
 * <p>Stated once, at the configuration, because it was twice a per-dependency exclusion and twice missed —
 * once when the proxy itself was added and once when its test fixtures were. A rule that must be repeated on
 * every future dependency is a rule that will be forgotten on one of them.
 *
 * <p>Excluded at the consumer rather than removed at the source: the image genuinely needs it, and taking it
 * out would leave the proxy discarding its own logs in production.
 */
configurations.testImplementation { exclude(group = "org.slf4j", module = "slf4j-simple") }

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

    // The egress target's build context: the shell responders every allowlist claim in this module is
    // measured against. It is not in a source set either, so a change to what a target replies would
    // otherwise leave this task cached green.
    inputs.dir(rootProject.layout.projectDirectory.dir("services/runner/src/main/docker/egress-target"))
        .withPropertyName("egressTargetBuildContext")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // Everything the proxy image is built from — Dockerfile, proxy jar, and its whole runtime classpath.
    inputs.files(proxyImageContext)
        .withPropertyName("egressProxyImageContext")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    val contextPath = proxyImageContext.elements.map { it.single().asFile.absolutePath }
    doFirst { systemProperty("kaas.egress.proxy.context", contextPath.get()) }
}
