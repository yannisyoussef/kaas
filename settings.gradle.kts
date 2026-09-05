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
include(":apps:api", ":services:runner", ":services:egress-proxy", ":tests:pipeline")
