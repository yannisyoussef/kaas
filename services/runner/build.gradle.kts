import org.gradle.api.attributes.Usage

dependencies {
    // The container-runtime client lives here and only here. The control plane's own build fails if it ever
    // acquires this dependency, and this module's build fails if it acquires Karate, an object store, or a
    // secret provider: the trusted launcher is allowed to talk to a daemon precisely because it has no
    // business reason to touch user content or credentials.
    implementation("com.github.docker-java:docker-java-core:3.4.1")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:3.4.1")

    // JSON, for validating the command the control plane sends and building the result that goes back.
    //
    // The runner parses and digests the command with its OWN code rather than sharing the control plane's,
    // and that is the point: two implementations that agree are evidence the document means the same thing on
    // both sides, while one shared implementation agreeing with itself is evidence of nothing. This module
    // cannot depend on :apps:api anyway — the build forbids it — so the independence is structural rather
    // than a convention somebody has to keep.
    implementation("tools.jackson.core:jackson-databind:3.1.5")

    // The programmable authoritative DNS server, shared with the proxy's own suite rather than duplicated.
    // This brings no production code into the runner: test fixtures are a separate source set, and the
    // launcher's runtime classpath is unaffected — the dependency guard below still checks both classpaths.
    testImplementation(testFixtures(project(":services:egress-proxy")))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

/**
 * The egress proxy's image build context, obtained through dependency resolution rather than by reaching into
 * another project's build directory.
 *
 * <p>Reaching across works right up until it silently does not: the test can run before the other project has
 * produced anything, and the evidence is then an image built from whatever a previous build left behind.
 * Resolving it means Gradle builds it first and treats its contents as an input, so editing the proxy's
 * Dockerfile or changing a line of proxy code makes these tests run again instead of reporting UP-TO-DATE.
 */
val proxyImageContext: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, "kaas-proxy-image-context")) }
}

dependencies {
    proxyImageContext(project(":services:egress-proxy"))
}

tasks.withType<Test>().configureEach {
    // Sandbox tests start real containers. Running them in parallel would make resource-ceiling
    // observations depend on what another test happened to be doing at the time.
    maxParallelForks = 1

    // The probe and its Dockerfile are the source of every behavioural claim these tests make, and neither
    // lives in a source set, so Gradle did not consider them inputs: removing the base image's digest pin left
    // the test task UP-TO-DATE and the build green. Any mutation confined to the probe was a false pass.
    inputs.dir(layout.projectDirectory.dir("src/main/docker"))
        .withPropertyName("probeBuildContext")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // Credentials the sandbox must never inherit, present in the launcher's own environment so that the
    // non-inheritance assertion is exercised rather than merely written. Without these the test asserts the
    // absence of names nothing ever set.
    environment("AWS_SECRET_ACCESS_KEY", "kaas-canary-must-not-cross-the-boundary")
    environment("GITHUB_TOKEN", "kaas-canary-must-not-cross-the-boundary")
    environment("KAAS_DATABASE_PASSWORD", "kaas-canary-must-not-cross-the-boundary")

    // The shared mandatory-control contract, for the same reason: read at runtime, so invisible to the
    // up-to-date check unless declared. A control added to the gate without the contract being updated must
    // fail the build, and it cannot fail a test that never runs.
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

    // Everything the proxy image is built from — the Dockerfile, the proxy jar, and every jar on its runtime
    // classpath. Declared as an input so that a change to any of them invalidates the security tests that
    // make claims about how the proxy behaves.
    inputs.files(proxyImageContext)
        .withPropertyName("egressProxyImageContext")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // Where those files are, for the launcher to build from. The build context is repository-controlled and
    // produced by this build; no caller supplies a path, and no test may point this somewhere else.
    val contextPath = proxyImageContext.elements.map { it.single().asFile.absolutePath }
    doFirst { systemProperty("kaas.egress.proxy.context", contextPath.get()) }
}

/**
 * The egress security suites, in their own task so they get their own mandatory CI job.
 *
 * <p>Separate from {@code test} for the same reason the hostile-execution suite has its own job: a security
 * gate hidden inside a general build is a gate whose failure is one line in a long log, and one that can be
 * dropped by an exclusion nobody notices. It is also a practical matter — these build several images and run
 * a proxy, a target, and a sandbox, and doubling that inside another job's time budget puts two launchers on
 * one daemon, which is the condition the existing job split exists to avoid.
 *
 * <p>{@code check} depends on it, so a local full build still runs everything.
 */
val egressSecurityTest = tasks.register<Test>("egressSecurityTest") {
    group = "verification"
    description = "Real Docker topology, real DNS, real proxy: the enforceable-egress security suites."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter {
        includeTestsMatching("com.kaas.runner.sandbox.Egress*")
        includeTestsMatching("com.kaas.runner.gate.EgressEnforcementGateTests")
    }
}

/**
 * The security gate, run under the mediating runtime.
 *
 * <p>Its own task, and deliberately NOT wired into `check`. These need a daemon with `runsc` registered;
 * Docker Desktop offers no supported way to install a runtime into its embedded VM, so requiring them locally
 * would break the ordinary build on the primary development platform. The evidence lives in a mandatory CI job
 * where it cannot be skipped instead.
 *
 * <p>The consequence is worth stating rather than leaving to be inferred: **a green local build proves nothing
 * about the stronger runtime.** Only `strong-runtime-gate` does.
 */
val strongRuntimeTest = tasks.register<Test>("strongRuntimeTest") {
    group = "verification"
    description = "Runs the hostile-execution probe under the mediating runtime. Requires runsc on the daemon."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter {
        includeTestsMatching("com.kaas.runner.sandbox.StrongRuntimeBoundaryTests")
        // Revocation under the real mediating runtime. Named explicitly rather than by a package glob: the
        // gate's evidence step asserts which suites ran, and a glob would let a renamed class silently drop
        // out of a mandatory gate while the task still reported success.
        includeTestsMatching("com.kaas.runner.sandbox.StrongRuntimeAuthorityRevocationTests")
        // Source delivery under the mediating runtime. Named explicitly for the same reason: the gate asserts
        // which suites produced its evidence, and a glob would let this drop out silently.
        // The mediated source filesystem. Named explicitly for the same reason as its neighbours: the gate
        // asserts which suites produced its evidence, and a glob would let a renamed class drop out silently.
        includeTestsMatching("com.kaas.runner.sandbox.MediatedSourceFilesystemBoundaryTests")
    }
}

tasks.named<Test>("test") {
    // Excluded here because they run in egressSecurityTest above. Running them in both would double a
    // Docker-heavy suite and put two launchers on one daemon.
    filter {
        excludeTestsMatching("com.kaas.runner.sandbox.Egress*")
        excludeTestsMatching("com.kaas.runner.gate.EgressEnforcementGateTests")
        // Needs a runtime this host may not have. Excluded here rather than made to skip: a suite that skips
        // itself when its subject is absent reports the same green as one that proved something.
        excludeTestsMatching("com.kaas.runner.sandbox.StrongRuntimeBoundaryTests")
        excludeTestsMatching("com.kaas.runner.sandbox.StrongRuntimeAuthorityRevocationTests")
        excludeTestsMatching("com.kaas.runner.sandbox.MediatedSourceFilesystemBoundaryTests")
    }
}

/**
 * Produces a signed sandbox security attestation for the runtime this task runs on.
 *
 * <p>The gates run for real: a probe image is built, a sandbox is launched under the hardened profile, and its
 * observations are signed. There is no input for a control verdict — the whole point of the slice is that an
 * operator transports the artifact and no longer authors its security claims.
 *
 * <p>The signing key is named by a FILE PATH. A key passed as a Gradle property would appear in the daemon's
 * command line and in build scans; a path does not.
 *
 * <p><strong>An attestation produced here describes THIS host.</strong> One produced on a CI runner describes
 * the CI runner and nothing else, which is why its runtime subject is different and why a control plane must
 * be configured to accept a subject before evidence for it means anything.
 */
val produceSandboxSecurityAttestation = tasks.register<JavaExec>("produceSandboxSecurityAttestation") {
    group = "verification"
    description = "Runs the security gates on this host and writes one signed attestation describing them."
    mainClass.set("com.kaas.runner.attestation.SandboxSecurityAttestationCli")
    classpath = sourceSets["main"].runtimeClasspath

    // The proxy image context, resolved rather than guessed at, so egress evidence describes the image this
    // build produced rather than whatever a previous build left in another module's directory. Declared as an
    // input, which is also what makes Gradle build it before this task runs — without that the path is queried
    // before the producing task has completed.
    inputs.files(proxyImageContext)
        .withPropertyName("egressProxyImageContext")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    val proxyContext = proxyImageContext.elements.map { it.single().asFile.absolutePath }
    doFirst {
        systemProperty("kaas.attestation.key-id", providers.gradleProperty("kaasAttestationKeyId").get())
        systemProperty(
            "kaas.attestation.private-key-file",
            providers.gradleProperty("kaasAttestationPrivateKeyFile").get())
        systemProperty(
            "kaas.attestation.runtime-subject",
            providers.gradleProperty("kaasAttestationRuntimeSubject").get())
        systemProperty(
            "kaas.attestation.output",
            providers.gradleProperty("kaasAttestationOutput").getOrElse("build/sandbox-security-attestation.json"))
        systemProperty(
            "kaas.attestation.include-egress",
            providers.gradleProperty("kaasAttestationIncludeEgress").getOrElse("false"))
        // Which sandbox runtime this host is being attested for. Defaults to the baseline, because that is
        // what a deployment that has not been told otherwise is running.
        systemProperty(
            "kaas.attestation.sandbox-runtime",
            providers.gradleProperty("kaasAttestationSandboxRuntime").getOrElse("docker"))
        systemProperty("kaas.attestation.probe-context", layout.projectDirectory.dir("src/main/docker/probe").asFile.absolutePath)
        systemProperty("kaas.attestation.proxy-image-context", proxyContext.get())
    }
}

tasks.named("check") { dependsOn(egressSecurityTest) }

/**
 * The launcher may hold a container runtime client. It may not hold anything that would give it a reason to
 * put user content or credentials inside a sandbox.
 */
val verifyLauncherHasNoUserContentDependencies = tasks.register("verifyLauncherHasNoUserContentDependencies") {
    group = "verification"
    description = "Fails if the trusted launcher acquires Karate, object-store, or secret-provider dependencies."
    doLast {
        // Both classpaths. The test classpath is where someone would first try running Karate feature files
        // against the sandbox, and it is the JVM the security gate itself runs in — checking only the runtime
        // classpath left exactly that door open.
        val artifacts = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts +
            configurations.testRuntimeClasspath.get().resolvedConfiguration.resolvedArtifacts
        val forbidden = artifacts
            .map { "${it.moduleVersion.id.group}:${it.name}" }
            .filter {
                it.startsWith("com.intuit.karate:") ||
                    it.startsWith("io.minio:") ||
                    it.startsWith("org.springframework.vault:") ||
                    it.startsWith("com.bettercloud:") ||
                    it.startsWith("software.amazon.awssdk:secretsmanager") ||
                    it.startsWith("com.amazonaws:aws-java-sdk-secretsmanager") ||
                    it.startsWith("com.azure:azure-security-keyvault") ||
                    it.startsWith("com.google.cloud:google-cloud-secretmanager") ||
                    // The daemon-privileged module must not acquire the control plane, which would hand it the
                    // datasource and JWT configuration in a single line.
                    it.contains("kaas:api")
            }
            .distinct()
        check(forbidden.isEmpty()) { "Forbidden launcher dependencies found in services/runner: $forbidden" }
    }
}

tasks.named("check") {
    dependsOn(verifyLauncherHasNoUserContentDependencies)
}
