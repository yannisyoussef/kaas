pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}
rootProject.name = "kaas"
// :tests:pipeline exists because NEITHER other module can host the full-pipeline test.
//
// :apps:api's build fails if it acquires a container runtime, and :services:runner's build fails if it
// acquires the control plane. Those guards are load-bearing — they are why the launcher may talk to a Docker
// daemon at all — so the test that needs both has to live somewhere that depends on both, and neither of them
// is that place. A third module is the honest answer; weakening either guard to avoid it would trade a real
// security boundary for build convenience.
// :services:egress-proxy is the trusted egress enforcement point. It is a separate module because it is a
// separate trust domain: it holds no Docker client, so a compromise of the proxy cannot start containers, and
// it holds no control-plane code, so the canonicalization it applies to a request is an independent
// implementation of the written contract rather than the same code agreeing with itself.
// :services:karate-engine is the ONLY module permitted to depend on Karate, and it is separate for the same
// reason :services:egress-proxy is: it is a different trust domain. Karate resolves arbitrary classes from
// its own classpath, so whatever is on this module's runtime classpath is reachable from tenant source.
// Keeping it in its own module is what makes "a privileged platform class is absent from the engine" a fact
// about the build rather than a hope about packaging.
include(":apps:api", ":services:runner", ":services:egress-proxy", ":services:karate-engine", ":tests:pipeline")
