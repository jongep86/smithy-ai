package dev.smithyai.orchestrator.testing;

import static org.junit.jupiter.api.Assertions.*;

import dev.smithyai.orchestrator.config.RuntimeConfig.KubernetesRuntimeConfig;
import dev.smithyai.orchestrator.service.docker.KubernetesRuntime;
import dev.smithyai.orchestrator.service.docker.dto.ContainerSpec;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The Kubernetes runtime against a real cluster: what the mock server cannot
 * show, namely exec over the API, stdin, exit codes, timeouts, and a task's
 * files surviving its pod.
 *
 * <p>Runs only when {@code SMITHY_K8S_IT_NAMESPACE} names a namespace the
 * current kubeconfig may create StatefulSets, pods and claims in. It uses a
 * plain busybox image, so it tests the runtime, not the task image.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KubernetesLifecycleIT {

    private static final String NAME = "smithy-it_lifecycle.1";

    private KubernetesClient client;
    private KubernetesRuntime runtime;

    @BeforeAll
    void setUp() {
        String namespace = System.getenv("SMITHY_K8S_IT_NAMESPACE");
        org.junit.jupiter.api.Assumptions.assumeTrue(
            namespace != null && !namespace.isBlank(),
            "SMITHY_K8S_IT_NAMESPACE not set"
        );
        client = new KubernetesClientBuilder().build();
        runtime = new KubernetesRuntime(
            client,
            new KubernetesRuntimeConfig(
                namespace,
                null,
                System.getenv("SMITHY_K8S_IT_STORAGE_CLASS"),
                "1Gi",
                List.of(),
                null,
                null,
                List.of(),
                Map.of(),
                180
            )
        );
        runtime.remove(NAME);
    }

    @AfterAll
    void tearDown() {
        if (runtime != null) runtime.remove(NAME);
        if (client != null) client.close();
    }

    @Test
    @Order(1)
    void createStartsARunningManagedContainer() {
        runtime.create(
            new ContainerSpec(
                NAME,
                "busybox:1.37",
                List.of("sh", "-c", "touch /tmp/smithy-init-done; exec sleep 2147483647"),
                Map.of("smithy.managed", "true", "smithy.workflow", "it"),
                Map.of(),
                Map.of("FROM_SPEC", "spec-value")
            )
        );

        assertEquals(Optional.of(true), runtime.isRunning(NAME));
        assertTrue(runtime.listManaged(false).contains(NAME));
        assertEquals(0, exec(List.of("test", "-f", "/tmp/smithy-init-done")).exitCode());
    }

    @Test
    @Order(2)
    void execHonoursWorkdirEnvironmentAndStdin() {
        var result = runtime.exec(
            NAME,
            List.of("sh", "-c", "echo \"$FOO $FROM_SPEC\"; pwd; cat"),
            Map.of("FOO", "it's $HOME"),
            "/workspace",
            "from stdin".getBytes(StandardCharsets.UTF_8),
            Duration.ofSeconds(30)
        );

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("it's $HOME spec-value\n/workspace\nfrom stdin", result.stdout());
    }

    @Test
    @Order(2)
    void largeStdinArrivesWhole() {
        byte[] payload = "0123456789abcdef".repeat(32 * 1024).getBytes(StandardCharsets.UTF_8);

        var result = runtime.exec(NAME, List.of("wc", "-c"), null, "/workspace", payload, Duration.ofSeconds(60));

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals(String.valueOf(payload.length), result.stdout().strip());
        assertEquals("", exec(List.of("sh", "-c", "ls /tmp/.smithy-stdin-* 2>/dev/null")).stdout());
    }

    @Test
    @Order(3)
    void exitCodesAndStderrComeBack() {
        var result = exec(List.of("sh", "-c", "echo oops >&2; exit 3"));

        assertEquals(3, result.exitCode());
        assertEquals("oops\n", result.stderr());
    }

    @Test
    @Order(4)
    void aCommandPastItsTimeoutReturns124() {
        long start = System.nanoTime();
        var result = runtime.exec(NAME, List.of("sleep", "30"), null, null, null, Duration.ofSeconds(2));

        assertEquals(124, result.exitCode());
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 15);
    }

    @Test
    @Order(5)
    void filesSurviveTheirPod() throws Exception {
        byte[] state = "{\"stage\":\"plan\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals(
            0,
            runtime.exec(NAME, List.of("sh", "-c", "cat > /tmp/smithy-state.json"), null, null, state, null).exitCode()
        );
        assertEquals(0, exec(List.of("sh", "-c", "echo w > /workspace/w && echo r > /root/r")).exitCode());

        String pod = client
            .pods()
            .inNamespace(System.getenv("SMITHY_K8S_IT_NAMESPACE"))
            .withLabel("smithy.managed", "true")
            .list()
            .getItems()
            .stream()
            .map(p -> p.getMetadata().getName())
            .filter(n -> n.startsWith("smithy-it-lifecycle"))
            .findFirst()
            .orElseThrow();
        client.pods().inNamespace(System.getenv("SMITHY_K8S_IT_NAMESPACE")).withName(pod).delete();

        // ensureRunning's path: not running, so start, which waits for the replacement.
        assertTrue(runtime.start(NAME));
        assertArrayEquals(state, runtime.execForBytes(NAME, List.of("cat", "/tmp/smithy-state.json"), null));
        assertEquals("w\nr\n", exec(List.of("sh", "-c", "cat /workspace/w /root/r")).stdout());
        assertTrue(runtime.logs(NAME, 10) != null);
    }

    @Test
    @Order(6)
    void removeLeavesNothingBehind() {
        runtime.remove(NAME);

        assertFalse(runtime.exists(NAME));
        assertFalse(runtime.listManaged(true).contains(NAME));
        assertTrue(
            client
                .persistentVolumeClaims()
                .inNamespace(System.getenv("SMITHY_K8S_IT_NAMESPACE"))
                .withLabel("smithy.managed", "true")
                .list()
                .getItems()
                .isEmpty()
        );
    }

    private dev.smithyai.orchestrator.service.docker.dto.ExecResult exec(List<String> command) {
        return runtime.exec(NAME, command, null, null, null, null);
    }
}
