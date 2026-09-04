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
    @DisplayName("a validated command carries no feature source, bundle, or content of any kind")
    void theCommandTheLoopHoldsCarriesNoSource() {
        RecordComponent[] components = ValidatedCommand.class.getRecordComponents();

        // Anti-vacuity: if this ever reflects over an empty or unrelated type, the loop below proves nothing.
        assertThat(components)
                .as("ValidatedCommand must actually have components for this test to mean anything")
                .hasSizeGreaterThan(10);

        for (RecordComponent component : components) {
            String name = component.getName().toLowerCase(Locale.ROOT);
            assertThat(SOURCE_BEARING)
                    .as("ValidatedCommand.%s looks like it carries tenant content into the executor",
                            component.getName())
                    .noneMatch(name::contains);
            // Every component is a scalar identity, instant, or enumeration-like string. A collection of
            // objects would be the shape source content arrives in.
            assertThat(component.getType())
                    .as("ValidatedCommand.%s must be a simple value, not a structure that could hold content",
                            component.getName())
                    .isIn(java.util.UUID.class, String.class, long.class, int.class,
                            java.time.Instant.class, List.class);
        }

        // The one collection it does carry is tags, and tags are selection labels rather than content.
        assertThat(Arrays.stream(components)
                        .filter(component -> component.getType() == List.class)
                        .map(RecordComponent::getName)
                        .toList())
                .containsExactly("tags");
    }

    @Test
    @DisplayName("the runner's control-plane client cannot fetch a source bundle")
    void theClientOffersNoWayToFetchSource() {
        List<String> methods = Arrays.stream(ControlPlaneClient.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .toList();

        assertThat(methods)
                .as("the client must expose the calls this slice actually makes")
                .contains("authorize", "advancePhase", "submitResult");

        // The source-bundle endpoint exists on the control plane and this client has no method for it. That is
        // the point: the capability to redeem a bundle is issued, and the runner has nowhere to spend it.
        for (String method : methods) {
            String name = method.toLowerCase(Locale.ROOT);
            assertThat(SOURCE_BEARING)
                    .as("ControlPlaneClient.%s would give the executor a way to fetch tenant content", method)
                    .noneMatch(name::contains);
        }
    }

    @Test
    @DisplayName("nothing in the execution package mentions the source-bundle endpoint or its capability header")
    void theExecutionPathNamesNoSourceEndpoint() throws Exception {
        // The endpoint and its header are strings, so a dependency rule would not catch them — a single
        // hand-written path would be enough to reach the bundle. This reads the compiled classes' constant
        // pools by proxy: the source files themselves.
        var root = java.nio.file.Path.of("src", "main", "java", "com", "kaas", "runner");
        var executionSources = java.nio.file.Files.walk(root.toFile().isDirectory()
                        ? root
                        : java.nio.file.Path.of("services", "runner", "src", "main", "java", "com", "kaas", "runner"))
                .filter(path -> path.toString().endsWith(".java"))
                .toList();

        assertThat(executionSources)
                .as("this test must actually be reading the runner's sources")
                .hasSizeGreaterThan(10);

        for (var path : executionSources) {
            String body = java.nio.file.Files.readString(path);
            assertThat(body)
                    .as("%s references the source-bundle endpoint", path.getFileName())
                    .doesNotContain("/source-bundles");
            assertThat(body)
                    .as("%s references the source capability header", path.getFileName())
                    .doesNotContain("X-KaaS-Source-Capability");
        }
    }
}
