// THE ONLY MODULE THAT MAY DEPEND ON KARATE.
//
// Karate is an interpreter with unrestricted Java interop: KAAS-20 read the bytecode and found
// `Java.type(name)` resolving to `Class.forName` on the context classloader with no allowlist and no
// configuration gate. Tenant source is therefore arbitrary JVM code, and the classpath of the process that
// runs it is a security control rather than a packaging detail.
//
// That is why this is its own module. Everything on this module's runtime classpath is reachable from tenant
// source; everything not on it is not. Keeping Karate out of the runner and the control plane is not tidiness
// — it is the difference between "a platform class is absent" and "a platform class is present but we hope
// nobody looks".
//
// The forbidden-dependency guards in the other modules are the other half of that statement, and they name
// both Karate coordinates.

dependencies {
    // PINNED EXACTLY, and to the coordinates that are actually maintained. `com.intuit.karate` stopped at
    // 1.4.1 in October 2023; 2.x lives at `io.karatelabs`. No range, no `+`, no `latest`: the version is part
    // of the security adjudication and changing it means redoing KAAS-20's engine analysis.
    implementation("io.karatelabs:karate-core:2.1.2")

    // NETTY, FORCED PAST ONE ADVISORY.
    //
    // Karate 2.1.2 resolves netty 4.2.16.Final, which carries GHSA-8c42-7qj2-3j46 (MODERATE): CorsHandler
    // overwrites an existing `Vary` header, enabling cache poisoning. Fixed in 4.2.17.Final.
    //
    // The affected class is a SERVER-side CORS handler and this engine runs features as a client, so the
    // path is not reachable here. That is an argument, and pinning the fix is a fact -- a patch bump inside
    // 4.2.x costs nothing, and "not reachable today" is the kind of reasoning that stops being true when
    // somebody enables Karate's mock server.
    constraints {
        implementation("io.netty:netty-codec-http:4.2.17.Final") {
            because("GHSA-8c42-7qj2-3j46: CorsHandler overwrites Vary; fixed in 4.2.17.Final")
        }
        implementation("io.netty:netty-handler:4.2.17.Final") {
            because("kept in step with netty-codec-http so one netty version is on the classpath")
        }
    }

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

/**
 * Assembles the engine image build context.
 *
 * <p>The same shape the egress proxy uses, and for the same reason: a consumer that reached across into
 * another project's build directory would eventually build an image from whatever was left over from a
 * previous build. The bootstrap's C source comes from the runner module because there is one copy of it, and
 * two files kept identical by hand is the arrangement that produces a measurement of a program nobody edited.
 */
val engineImageContext = tasks.register<Sync>("engineImageContext") {
    group = "build"
    description = "Assembles the repository-controlled Karate engine image build context."
    into(layout.buildDirectory.dir("engine-image-context"))
    from(layout.projectDirectory.dir("src/main/docker"))
    from(tasks.named("jar")) { into("lib") }
    from(configurations.runtimeClasspath) { into("lib") }
    from(rootProject.layout.projectDirectory.dir("services/runner/src/main/docker/probe")) {
        include("source-bootstrap.c")
    }
}

/**
 * Published so the runner's tests get the context through dependency resolution rather than by reaching into
 * this project's build directory.
 */
val engineImageContextElements by configurations.registering {
    isCanBeResolved = false
    isCanBeConsumed = true
    attributes {
        attribute(
            org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE,
            objects.named(org.gradle.api.attributes.Usage::class.java, "kaas-engine-image-context"))
    }
}

artifacts { add(engineImageContextElements.name, layout.buildDirectory.dir("engine-image-context")) { builtBy(engineImageContext) } }

tasks.named<Test>("test") { useJUnitPlatform() }
