package com.kaas.runner.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.runner.client.ControlPlaneClient;
import com.kaas.runner.command.ValidatedCommand;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The synthetic executor has no path to tenant feature source.
 *
 * <p>This slice executes a platform-owned workload and nothing else. That is easy to state and easy to erode:
 * the command <em>describes</em> a source bundle, the control plane <em>can</em> serve one, and a capability
 * that redeems it exists. All that would be needed is one call.
 *
 * <p>So the property is made structural rather than asserted about behaviour. The execution loop consumes a
 * {@link ValidatedCommand}, and that type carries no source content at all — not the bundle, not the features,
 * not their digests. There is nothing to pass to a sandbox because the value does not exist at that point in
 * the program. These tests pin that shape, so removing it is a visible change rather than a quiet one.
 *
 * <p>Reflection rather than ArchUnit because the claim is about the SHAPE OF A TYPE, not about package
 * dependencies. A dependency rule would say the execution package does not import a source class; it would say
 * nothing about a {@code String featureSource} field appearing on the command the loop already holds.
 */
class SourceNonUseTests {

    /** Anything that would name or carry tenant source content. */
    private static final List<String> SOURCE_BEARING = List.of(
            "source", "bundle", "feature", "gherkin", "script", "content", "body", "payload");

    @Test
    @DisplayName("a validated command names the source bundle but carries none of its bytes")
    void theCommandNamesSourceWithoutCarryingIt() {
        // THIS INVARIANT CHANGED, DELIBERATELY, AND NARROWED RATHER THAN LOOSENED.
        //
        // The command used to mention nothing about source at all. It now names the bundle it authorizes --
        // its digest, and each feature's identity, logical path and content digest -- because the runner must
        // be able to REFUSE a bundle that is not the authorized one, and it cannot do that without knowing
        // what the authorized one is.
        //
        // What it must never carry is content. A name check would not express that: `sourceBundle` is a
        // perfectly good field name for a record of digests. So this asserts the shape instead -- every value
        // reachable from the command is an identifier, a path, a digest, a number or a flag, and nothing
        // anywhere is a byte carrier.
        assertNoContentCarriers(ValidatedCommand.class, new java.util.HashSet<>());

        // And the bundle's per-feature shape is exactly identity plus digest.
        assertThat(Arrays.stream(ValidatedCommand.Feature.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList())
                .containsExactly("featureId", "revisionId", "logicalPath", "contentDigest");
    }

    /**
     * Walks a record's components and refuses any type that could hold file content.
     *
     * <p>Recursive, because a byte array nested inside a nested record is still a byte array reaching the
     * execution loop. Types outside this repository's own packages are not descended into: the check is about
     * what this codebase declares, and {@code String} has no components worth walking.
     */
    private static void assertNoContentCarriers(Class<?> type, java.util.Set<Class<?>> seen) {
        if (!seen.add(type) || !type.isRecord()) {
            return;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            Class<?> componentType = component.getType();
            assertThat(componentType.isArray() && componentType.getComponentType() == byte.class)
                    .as("%s.%s is a byte array, which is source content reaching the executor",
                            type.getSimpleName(), component.getName())
                    .isFalse();
            assertThat(java.io.InputStream.class.isAssignableFrom(componentType)
                            || java.nio.ByteBuffer.class.isAssignableFrom(componentType)
                            || java.io.Reader.class.isAssignableFrom(componentType))
                    .as("%s.%s could stream source content into the executor",
                            type.getSimpleName(), component.getName())
                    .isFalse();
            if (componentType.getName().startsWith("com.kaas.")) {
                assertNoContentCarriers(componentType, seen);
            }
            // Generic element types too: a List<byte[]> is a byte array behind one level of indirection.
            if (component.getGenericType() instanceof java.lang.reflect.ParameterizedType parameterized) {
                for (var argument : parameterized.getActualTypeArguments()) {
                    if (argument instanceof Class<?> element) {
                        assertThat(element.isArray() && element.getComponentType() == byte.class)
                                .as("%s.%s holds byte arrays", type.getSimpleName(), component.getName())
                                .isFalse();
                        if (element.getName().startsWith("com.kaas.")) {
                            assertNoContentCarriers(element, seen);
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("the client can redeem exactly one authorized bundle, and cannot browse source")
    void theClientOffersOnlyCapabilityBoundRedemption() {
        // THIS INVARIANT CHANGED, DELIBERATELY. Until this slice the runner had no way to fetch tenant source
        // at all, and that absence was the property. Source delivery replaces it with a narrower one: the
        // runner may spend a capability it was issued for one assignment, and has no other way to reach
        // source of any kind.
        List<String> methods = Arrays.stream(ControlPlaneClient.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .toList();

        assertThat(methods)
                .as("the client must expose the calls this slice actually makes")
                .contains("authorize", "advancePhase", "submitResult", "redeemSourceBundle");

        // Exactly one source-bearing method, and it is the capability redemption. Anything else -- a fetch by
        // feature, by revision, by run -- would be a browsing API, which is what the worker must not have.
        List<String> sourceBearing = methods.stream()
                .filter(method -> {
                    String name = method.toLowerCase(Locale.ROOT);
                    return SOURCE_BEARING.stream().anyMatch(name::contains);
                })
                .toList();
        assertThat(sourceBearing)
                .as("the only way to reach source must be redeeming the capability the command carried")
                .containsExactly("redeemSourceBundle");
    }

    @Test
    @DisplayName("the source endpoint is named in one place, and never in the execution path")
    void theSourceEndpointIsNamedOnlyWhereItIsRedeemed() throws Exception {
        // The endpoint and its header are strings, so a dependency rule would not catch them -- one
        // hand-written path anywhere would be another way to reach the bundle. This reads the sources.
        var root = java.nio.file.Path.of("src", "main", "java", "com", "kaas", "runner");
        var sources = java.nio.file.Files.walk(root.toFile().isDirectory()
                        ? root
                        : java.nio.file.Path.of("services", "runner", "src", "main", "java", "com", "kaas", "runner"))
                .filter(path -> path.toString().endsWith(".java"))
                .toList();

        assertThat(sources).as("this test must actually be reading the runner's sources").hasSizeGreaterThan(10);

        List<String> naming = sources.stream()
                .filter(path -> {
                    try {
                        String body = java.nio.file.Files.readString(path);
                        return body.contains("/source-bundles") || body.contains("X-KaaS-Source-Capability");
                    } catch (java.io.IOException unreadable) {
                        return false;
                    }
                })
                .map(path -> path.getFileName().toString())
                .toList();

        // One file: the client that redeems. The execution package holds the token and hands it to that
        // client; it does not know how to spend it itself, which keeps the number of places that could grow a
        // second source path at one.
        assertThat(naming).containsExactly("ControlPlaneClient.java");
    }

}
