package dev.smithyai.orchestrator.testing;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.smithyai.orchestrator.config.RuntimeConfig.KubernetesRuntimeConfig;
import dev.smithyai.orchestrator.service.docker.ContainerRuntime;
import dev.smithyai.orchestrator.service.docker.KubernetesRuntime;
import dev.smithyai.orchestrator.service.docker.SandboxRuntime;
import dev.smithyai.orchestrator.service.docker.dto.ContainerSpec;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
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
 * A Kubernetes runtime against a real cluster: what the mock server cannot
 * show, namely exec over the API, stdin, exit codes, timeouts, and a task's
 * files surviving its pod.
 *
 * <p>Runs only when {@code SMITHY_K8S_IT_NAMESPACE} names a namespace the
 * current kubeconfig may create StatefulSets (or Sandboxes), pods and claims
 * in. {@code SMITHY_K8S_IT_BACKEND=sandbox} runs it against
 * {@link SandboxRuntime} and a cluster with agent-sandbox installed; the tests
 * that only make sense there (claim naming, suspend, expiry, the Ready
 * condition in a start timeout) are skipped on the StatefulSet backend.
 * {@code SMITHY_K8S_IT_FAILED_POD_POLICY} is passed through when set. It uses a
 * plain busybox image, so it tests the runtime, not the task image.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KubernetesLifecycleIT {

    private static final String NAME = "smithy-it_lifecycle.1";
    private static final String TIMEOUT_NAME = "smithy-it_lifecycle.timeout";
    private static final String EXPIRY_NAME = "smithy-it_lifecycle.expiry";
    private static final String IMAGE = "busybox:1.37";
    private static final List<String> INIT_AND_SLEEP = List.of(
        "sh",
        "-c",
        "touch /tmp/smithy-init-done; exec sleep 2147483647"
    );
    private static final ResourceDefinitionContext SANDBOX = new ResourceDefinitionContext.Builder()
        .withGroup("agents.x-k8s.io")
        .withVersion("v1beta1")
        .withKind("Sandbox")
        .withPlural("sandboxes")
        .withNamespaced(true)
        .build();

    private KubernetesClient client;
    private ContainerRuntime runtime;
    private String namespace;
    private boolean sandbox;

    @BeforeAll
    void setUp() {
        namespace = System.getenv("SMITHY_K8S_IT_NAMESPACE");
        assumeTrue(namespace != null && !namespace.isBlank(), "SMITHY_K8S_IT_NAMESPACE not set");
        client = new KubernetesClientBuilder().build();
        var config = config(180, null);
        sandbox = config.usesSandbox();
        runtime = runtimeFor(config);
        runtime.remove(NAME);
        runtime.remove(TIMEOUT_NAME);
        runtime.remove(EXPIRY_NAME);
    }

    @AfterAll
    void tearDown() {
        if (runtime != null) {
            runtime.remove(NAME);
            runtime.remove(TIMEOUT_NAME);
            runtime.remove(EXPIRY_NAME);
        }
        if (client != null) client.close();
    }

    @Test
    @Order(1)
    void createStartsARunningManagedContainer() {
        runtime.create(spec(NAME, IMAGE, INIT_AND_SLEEP));

        assertEquals(Optional.of(true), runtime.isRunning(NAME));
        assertTrue(runtime.listManaged(false).contains(NAME));
        assertEquals(0, exec(List.of("test", "-f", "/tmp/smithy-init-done")).exitCode());
    }

    @Test
    @Order(1)
    void sandboxPodAndClaimCarryTheSandboxName() {
        assumeTrue(sandbox, "sandbox backend only");
        String id = sandboxId(NAME);

        assertNotNull(client.pods().inNamespace(namespace).withName(id).get(), "pod named like the Sandbox");
        var claim = client.persistentVolumeClaims().inNamespace(namespace).withName("task-" + id).get();
        assertNotNull(claim, "claim task-<id>");
        assertEquals("true", claim.getMetadata().getLabels().get("smithy.managed"));
        assertEquals("Bound", claim.getStatus().getPhase());
        assertEquals("True", condition(sandboxes().withName(id).get(), "Ready").get("status"));
        String policy = System.getenv("SMITHY_K8S_IT_FAILED_POD_POLICY");
        if (policy != null && !policy.isBlank()) {
            assertEquals(policy, sandboxes().withName(id).get().get("spec", "failedPodPolicy"));
        }
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

        client.pods().inNamespace(namespace).withName(podOf(NAME)).delete();

        // ensureRunning's path: not running, so start, which waits for the replacement.
        assertTrue(runtime.start(NAME));
        assertArrayEquals(state, runtime.execForBytes(NAME, List.of("cat", "/tmp/smithy-state.json"), null));
        assertEquals("w\nr\n", exec(List.of("sh", "-c", "cat /workspace/w /root/r")).stdout());
        assertTrue(runtime.logs(NAME, 10) != null);
    }

    @Test
    @Order(6)
    void aSuspendedSandboxIsStoppedKeepsItsFilesAndStartResumesIt() {
        assumeTrue(sandbox, "sandbox backend only");
        String id = sandboxId(NAME);

        sandboxes()
            .withName(id)
            .edit(s -> {
                spec(s).put("operatingMode", "Suspended");
                return s;
            });
        client
            .pods()
            .inNamespace(namespace)
            .withName(id)
            .waitUntilCondition(p -> p == null, 90, TimeUnit.SECONDS);

        assertEquals(Optional.of(false), runtime.isRunning(NAME));
        assertFalse(runtime.listManaged(false).contains(NAME), "suspended is not running");
        assertTrue(runtime.listManaged(true).contains(NAME), "but still managed");
        assertNotNull(client.persistentVolumeClaims().inNamespace(namespace).withName("task-" + id).get());

        assertTrue(runtime.start(NAME), "start resumes a suspended Sandbox");
        assertEquals(Optional.of(true), runtime.isRunning(NAME));
        assertEquals("Running", sandboxes().withName(id).get().get("spec", "operatingMode"));
        assertEquals("w\nr\n", exec(List.of("sh", "-c", "cat /workspace/w /root/r")).stdout());
    }

    @Test
    @Order(7)
    void removeLeavesNothingBehind() {
        String pod = podOf(NAME);
        String id = sandbox ? sandboxId(NAME) : null;

        runtime.remove(NAME);

        assertFalse(runtime.exists(NAME));
        assertFalse(runtime.listManaged(true).contains(NAME));
        assertNull(client.pods().inNamespace(namespace).withName(pod).get(), "pod gone");
        assertTrue(
            client
                .persistentVolumeClaims()
                .inNamespace(namespace)
                .withLabel("smithy.managed", "true")
                .list()
                .getItems()
                .isEmpty()
        );
        if (sandbox) {
            assertNull(sandboxes().withName(id).get(), "Sandbox gone");
            assertNull(client.persistentVolumeClaims().inNamespace(namespace).withName("task-" + id).get());
        }
    }

    @Test
    @Order(8)
    void aStartTimeoutNamesTheReadyConditionAndThePodState() {
        assumeTrue(sandbox, "sandbox backend only");
        var impatient = runtimeFor(config(20, null));

        var error = assertThrows(RuntimeException.class, () ->
            impatient.create(spec(TIMEOUT_NAME, "busybox:no-such-tag-smithy-it", INIT_AND_SLEEP))
        );

        assertTrue(error.getMessage().contains("pod not running after 20s"), error.getMessage());
        assertTrue(error.getMessage().contains("Ready=False"), error.getMessage());
        assertTrue(error.getMessage().matches("(?s).*(ErrImagePull|ImagePullBackOff).*"), error.getMessage());

        impatient.remove(TIMEOUT_NAME);
        assertFalse(impatient.exists(TIMEOUT_NAME));
    }

    @Test
    @Order(9)
    void anExpiredSandboxIsReapedWithItsVolume() throws Exception {
        assumeTrue(sandbox, "sandbox backend only");
        var shortLived = runtimeFor(config(180, 60));

        shortLived.create(spec(EXPIRY_NAME, IMAGE, INIT_AND_SLEEP));
        String id = sandboxId(EXPIRY_NAME);
        assertEquals("Delete", sandboxes().withName(id).get().get("spec", "shutdownPolicy"));
        assertNotNull(sandboxes().withName(id).get().get("spec", "shutdownTime"));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
        while (sandboxes().withName(id).get() != null && System.nanoTime() < deadline) {
            Thread.sleep(2000);
        }

        assertNull(sandboxes().withName(id).get(), "the controller deleted the expired Sandbox");
        assertFalse(shortLived.exists(EXPIRY_NAME));
        client
            .persistentVolumeClaims()
            .inNamespace(namespace)
            .withName("task-" + id)
            .waitUntilCondition(c -> c == null, 60, TimeUnit.SECONDS);
        assertNull(client.pods().inNamespace(namespace).withName(id).get(), "its pod went with it");
    }

    // ── Helpers ──────────────────────────────────────────────

    private KubernetesRuntimeConfig config(int startTimeout, Integer maxLifetime) {
        return new KubernetesRuntimeConfig(
            namespace,
            null,
            System.getenv("SMITHY_K8S_IT_STORAGE_CLASS"),
            "1Gi",
            List.of(),
            null,
            null,
            List.of(),
            Map.of(),
            startTimeout,
            System.getenv("SMITHY_K8S_IT_BACKEND"),
            null,
            null,
            maxLifetime,
            System.getenv("SMITHY_K8S_IT_FAILED_POD_POLICY")
        );
    }

    private ContainerRuntime runtimeFor(KubernetesRuntimeConfig config) {
        return config.usesSandbox() ? new SandboxRuntime(client, config) : new KubernetesRuntime(client, config);
    }

    private static ContainerSpec spec(String name, String image, List<String> command) {
        return new ContainerSpec(
            name,
            image,
            command,
            Map.of("smithy.managed", "true", "smithy.workflow", "it"),
            Map.of(),
            Map.of("FROM_SPEC", "spec-value")
        );
    }

    private dev.smithyai.orchestrator.service.docker.dto.ExecResult exec(List<String> command) {
        return runtime.exec(NAME, command, null, null, null, null);
    }

    /** The managed pod of a container, found by its name annotation. */
    private String podOf(String name) {
        return client
            .pods()
            .inNamespace(namespace)
            .withLabel("smithy.managed", "true")
            .list()
            .getItems()
            .stream()
            .filter(p -> name.equals(p.getMetadata().getAnnotations().get("smithy.ai/container-name")))
            .map(p -> p.getMetadata().getName())
            .findFirst()
            .orElseThrow(() -> new AssertionError("no pod for " + name));
    }

    /** The Sandbox of a container, found by its name annotation. */
    private String sandboxId(String name) {
        return sandboxes()
            .withLabel("smithy.managed", "true")
            .list()
            .getItems()
            .stream()
            .filter(s -> name.equals(s.getMetadata().getAnnotations().get("smithy.ai/container-name")))
            .map(s -> s.getMetadata().getName())
            .findFirst()
            .orElseThrow(() -> new AssertionError("no Sandbox for " + name));
    }

    private io.fabric8.kubernetes.client.dsl.NonNamespaceOperation<
        GenericKubernetesResource,
        io.fabric8.kubernetes.api.model.GenericKubernetesResourceList,
        io.fabric8.kubernetes.client.dsl.Resource<GenericKubernetesResource>
    > sandboxes() {
        return client.genericKubernetesResources(SANDBOX).inNamespace(namespace);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> spec(GenericKubernetesResource sandbox) {
        return (Map<String, Object>) sandbox.getAdditionalProperties().get("spec");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> condition(GenericKubernetesResource sandbox, String type) {
        List<Map<String, Object>> conditions = sandbox.get("status", "conditions");
        return conditions
            .stream()
            .filter(c -> type.equals(c.get("type")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no " + type + " condition on " + sandbox.getMetadata().getName()));
    }
}
