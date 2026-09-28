package com.kaas.api.deployment;

import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The post-deploy synthetic check: exits 0 only when the deployed platform base can accept work.
 *
 * <pre>
 *   deploy-check --api http://&lt;api-management&gt;:8081 [--runner http://&lt;runner-health&gt;:9090 ...] [--timeout PT120S]
 * </pre>
 *
 * <p>It reads {@code /actuator/deployment} from the API's management port and requires {@code status=READY}:
 * database up, schema current, broker connection open, and at least one runner polling for work while holding
 * current sandbox evidence. Each {@code --runner} named is additionally required to answer its own readiness
 * endpoint with 200. It retries until the timeout, because a freshly started runner needs a moment to assess its
 * runtime and submit its evidence.
 *
 * <p>No tenant, no secret, no execution. Output is the status words the endpoints returned; it contains no
 * credential and no URL beyond the ones the operator passed in.
 */
public final class DeploymentCheck {
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private DeploymentCheck() {}

    public static void main(String[] arguments) {
        System.exit(run(arguments, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), System.out));
    }

    public static int run(String[] arguments, HttpClient http, PrintStream out) {
        URI api = null;
        List<URI> runners = new ArrayList<>();
        Duration timeout = Duration.ofSeconds(120);
        try {
            for (int index = 0; index < arguments.length; index++) {
                switch (arguments[index]) {
                    case "--api" -> api = URI.create(arguments[++index]);
                    case "--runner" -> runners.add(URI.create(arguments[++index]));
                    case "--timeout" -> timeout = Duration.parse(arguments[++index]);
                    default -> throw new IllegalArgumentException("unknown argument");
                }
            }
        } catch (RuntimeException invalid) {
            out.println("deploy_check=USAGE");
            return 2;
        }
        if (api == null || timeout.isNegative()) {
            out.println("deploy_check=USAGE");
            return 2;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        String last = "";
        while (true) {
            List<String> problems = new ArrayList<>();
            JsonNode status = get(http, api.resolve("/actuator/deployment"));
            if (status == null || !"READY".equals(status.path("status").asString(""))) {
                problems.add("api=" + (status == null ? "UNREACHABLE" : summary(status)));
            }
            for (int index = 0; index < runners.size(); index++) {
                JsonNode readiness = get(http, runners.get(index).resolve("/health/readiness"));
                if (readiness == null || !"UP".equals(readiness.path("status").asString(""))) {
                    problems.add("runner" + index + "=" + (readiness == null ? "NOT_READY" : summary(readiness)));
                }
            }
            if (problems.isEmpty()) {
                out.println("deploy_check=READY " + summary(status));
                return 0;
            }
            last = String.join(" ", problems);
            if (System.nanoTime() >= deadline) {
                out.println("deploy_check=NOT_READY " + last);
                return 1;
            }
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                out.println("deploy_check=NOT_READY " + last);
                return 1;
            }
        }
    }

    /** Status words and counts only, from a document whose shape this build defines. */
    private static String summary(JsonNode document) {
        StringBuilder words = new StringBuilder();
        document.properties().forEach(entry -> {
            if (entry.getValue().isValueNode() && words.length() < 400) {
                words.append(words.isEmpty() ? "" : ",").append(entry.getKey()).append(':')
                        .append(entry.getValue().asString(""));
            }
        });
        return words.toString();
    }

    /** The parsed body of a 200 response, or null for anything else. */
    private static JsonNode get(HttpClient http, URI uri) {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? JSON.readTree(response.body()) : null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception unreachable) {
            return null;
        }
    }
}
