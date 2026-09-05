import org.gradle.api.attributes.Usage

// Test fixtures, so the Docker topology suite in :services:runner can drive the SAME authoritative DNS
// server this module's own tests use. Two copies of a programmable DNS server is two things to keep correct,
// and the one that drifts is the one whose evidence stops meaning what it says.
plugins { `java-test-fixtures` }

dependencies {
    testFixturesImplementation("dnsjava:dnsjava:3.6.3")

    // An explicit DNS resolver, because the security property this proxy exists to provide is stated in terms
    // of a single resolution whose answers are inspected. InetAddress.getByName offers no control over the
    // server queried, no visibility of the full answer set, and a JVM-global cache whose TTL semantics are a
    // deployment property rather than a code property — none of which can be tested honestly.
    //
    // dnsjava is a narrow, mature, pure-Java implementation of the protocol (BSD-2-Clause) with no native code
    // and a small transitive footprint. Writing a DNS parser by hand for this would be the less safe option.
    implementation("dnsjava:dnsjava:3.6.3")

    // dnsjava logs through SLF4J. Without a binding it prints a warning to stderr on first use and discards
    // everything; with one, proxy logs are subject to the same redaction rules as everything else.
    implementation("org.slf4j:slf4j-api:2.0.16")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")

    // For the control-plane authorization exchange. Same major version as the runner uses.
    implementation("tools.jackson.core:jackson-databind:3.1.5")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

/**
 * The image build context: the Dockerfile plus exactly the jars the proxy runs.
 *
 * <p>Assembled by Gradle rather than written by hand so that the image cannot silently diverge from the code
 * that was compiled and tested. A stale jar copied into a context directory is an image running code nobody
 * reviewed in this build.
 */
val proxyImageContext = tasks.register<Sync>("proxyImageContext") {
    group = "build"
    description = "Assembles the repository-controlled egress-proxy image build context."
    into(layout.buildDirectory.dir("proxy-image-context"))
    from(layout.projectDirectory.dir("src/main/docker"))
    from(tasks.named("jar")) { into("lib") }
    from(configurations.runtimeClasspath) { into("lib") }
}

/**
 * Published so consumers get the context through dependency resolution rather than by reaching across into
 * another project's build directory. Reaching across works until it silently does not: the consumer's test can
 * run before this task has produced anything, and then the evidence is an image built from whatever was left
 * over from a previous build.
 */
val proxyImageContextElements by configurations.registering {
    isCanBeResolved = false
    isCanBeConsumed = true
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, "kaas-proxy-image-context")) }
}

artifacts {
    add(proxyImageContextElements.name, proxyImageContext.map { it.destinationDir }) {
        builtBy(proxyImageContext)
    }
}

tasks.withType<Test>().configureEach {
    // Redirects the JDK's own name resolution to a fixture file, so a test can give one name two different
    // answers: one from the controlled DNS server the proxy is supposed to use, and a different one from the
    // resolver it is supposed to never use. Without this the "connect to the classified address" mutation is
    // unobservable in process — both paths fail to connect and the test cannot tell them apart. It survived
    // the suite until this was added.
    systemProperty("jdk.net.hosts.file", layout.projectDirectory.file("src/test/resources/jdk-hosts").asFile.absolutePath)

    // The Dockerfile and entrypoint are the source of the image's security claims and live in no source set,
    // so Gradle would not otherwise treat them as inputs: a change to either would leave the tests that assert
    // those claims UP-TO-DATE, and the build would stay green across a real regression.
    inputs.dir(layout.projectDirectory.dir("src/main/docker"))
        .withPropertyName("proxyImageDockerContext")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The canonicalization contract is implemented here from the written specification. If the specification
    // changes and this implementation does not, the conformance test must run and fail rather than be skipped.
    inputs.file(rootProject.file("packages/api-contracts/egress-allowlist-canonicalization.md"))
        .withPropertyName("egressCanonicalizationContract")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

/**
 * The trusted proxy may hold a DNS client and a socket. It may not hold a container runtime, the control
 * plane, or anything that would give it a reason to hold tenant content.
 *
 * <p>The Docker exclusion is the load-bearing one: the proxy is deliberately reachable from an untrusted
 * sandbox, and a proxy that could talk to a daemon would turn any proxy compromise into container escape.
 */
val verifyProxyHasNoPrivilegedDependencies = tasks.register("verifyProxyHasNoPrivilegedDependencies") {
    group = "verification"
    description = "Fails if the trusted egress proxy acquires daemon, control-plane, or user-content dependencies."
    doLast {
        val artifacts = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts +
            configurations.testRuntimeClasspath.get().resolvedConfiguration.resolvedArtifacts
        val forbidden = artifacts
            .map { "${it.moduleVersion.id.group}:${it.name}" }
            .filter {
                it.startsWith("com.github.docker-java:") ||
                    it.startsWith("com.intuit.karate:") ||
                    it.startsWith("io.minio:") ||
                    it.startsWith("org.springframework") ||
                    it.contains("kaas:api") ||
                    it.contains("kaas:runner")
            }
            .distinct()
        check(forbidden.isEmpty()) { "Forbidden egress-proxy dependencies found: $forbidden" }
    }
}

tasks.named("check") { dependsOn(verifyProxyHasNoPrivilegedDependencies) }
