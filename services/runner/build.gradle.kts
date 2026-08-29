dependencies {
    // The container-runtime client lives here and only here. The control plane's own build fails if it ever
    // acquires this dependency, and this module's build fails if it acquires Karate, an object store, or a
    // secret provider: the trusted launcher is allowed to talk to a daemon precisely because it has no
    // business reason to touch user content or credentials.
    implementation("com.github.docker-java:docker-java-core:3.4.1")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:3.4.1")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
}

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
