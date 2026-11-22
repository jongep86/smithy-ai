package dev.smithyai.orchestrator.service.docker;

import static org.junit.jupiter.api.Assertions.*;

import dev.smithyai.orchestrator.config.RuntimeConfig.KubernetesRuntimeConfig;
import dev.smithyai.orchestrator.service.docker.dto.ContainerSpec;
import io.fabric8.kubernetes.api.model.ContainerStateBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true, https = false)
class SandboxRuntimeTest {

    private static final String NS = "smithy-tasks";

    KubernetesClient client;
    private SandboxRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = new SandboxRuntime(client, config(5));
    }

    // ── Manifest ─────────────────────────────────────────────

    @Test
    void sandboxCarriesWhatATaskContainerNeeds() {
        var sandbox = SandboxRuntime.sandboxFor(
            spec("Smithy_o.r-1"),
            config(300),
            Instant.parse("2026-11-22T10:00:00Z")
        );
        String id = sandbox.getMetadata().getName();
        Map<String, Object> spec = sandbox.get("spec");
        Map<String, Object> podTemplate = sandbox.get("spec", "podTemplate");
        Map<String, Object> pod = sandbox.get("spec", "podTemplate", "spec");
        List<Map<String, Object>> containers = sandbox.get("spec", "podTemplate", "spec", "containers");
        List<Map<String, Object>> claims = sandbox.get("spec", "volumeClaimTemplates");

        assertEquals("agents.x-k8s.io/v1beta1", sandbox.getApiVersion());
        assertEquals("Sandbox", sandbox.getKind());
        assertEquals(KubernetesNames.objectName("Smithy_o.r-1"), id);
        assertEquals("Smithy_o.r-1", sandbox.getMetadata().getAnnotations().get(TaskPods.NAME_ANNOTATION));
        assertEquals("true", sandbox.getMetadata().getLabels().get("smithy.managed"));
        assertEquals(id, sandbox.getMetadata().getLabels().get(TaskPods.ID_LABEL));

        assertEquals(false, spec.get("service"), "no headless Service: nothing dials a task pod");
        assertEquals("Running", spec.get("operatingMode"));
        assertFalse(spec.containsKey("shutdownTime"), "no expiry unless maxLifetime is set");
        assertFalse(spec.containsKey("failedPodPolicy"), "a v1.0.12 field is not sent unless configured");

        assertEquals(1, claims.size());
        assertEquals("task", ((Map<?, ?>) claims.getFirst().get("metadata")).get("name"));
        assertEquals(
            "10Gi",
            sandbox.get("spec", "volumeClaimTemplates", 0, "spec", "resources", "requests", "storage")
        );
        assertEquals(List.of("ReadWriteOnce"), sandbox.get("spec", "volumeClaimTemplates", 0, "spec", "accessModes"));

        assertEquals(sandbox.getMetadata().getLabels(), sandbox.get("spec", "podTemplate", "metadata", "labels"));
        assertEquals(
            sandbox.getMetadata().getAnnotations(),
            sandbox.get("spec", "podTemplate", "metadata", "annotations")
        );
        assertNotNull(podTemplate);
        assertEquals(false, pod.get("automountServiceAccountToken"), "task pods must not get a service account token");
        assertEquals(5L, pod.get("terminationGracePeriodSeconds"));
        assertEquals(List.of(Map.of("name", "ghcr-creds")), pod.get("imagePullSecrets"));
        assertEquals(1, containers.size());
        assertEquals("task", containers.getFirst().get("name"));
        assertEquals(List.of("smithy-init"), containers.getFirst().get("args"));
        assertEquals("claude-task:test", containers.getFirst().get("image"));
        assertEquals(
            "1Gi",
            sandbox.get("spec", "podTemplate", "spec", "containers", 0, "resources", "requests", "memory")
        );
        assertEquals("seed-root", sandbox.get("spec", "podTemplate", "spec", "initContainers", 0, "name"));
        assertEquals(
            "smithy-cache-npm",
            sandbox.get("spec", "podTemplate", "spec", "volumes", 0, "persistentVolumeClaim", "claimName")
        );
        assertFalse(pod.containsKey("runtimeClassName"));
        assertFalse(pod.containsKey("nodeSelector"));
    }

    @Test
    void expiryRuntimeClassAndFailedPodPolicyFollowTheConfig() {
        var config = new KubernetesRuntimeConfig(
            NS,
            "claude-task:test",
            null,
            null,
            List.of(),
            null,
            null,
            List.of(),
            Map.of(),
            300,
            "sandbox",
            "gvisor",
            Map.of("node-pool", "agents"),
            3600,
            "Recreate"
        );
        var sandbox = SandboxRuntime.sandboxFor(spec("smithy-o-r-1"), config, Instant.parse("2026-11-22T10:00:00.5Z"));

        assertEquals("2026-11-22T11:00:00Z", sandbox.get("spec", "shutdownTime"));
        assertEquals("Delete", sandbox.get("spec", "shutdownPolicy"));
        assertEquals("Recreate", sandbox.get("spec", "failedPodPolicy"));
        assertEquals("gvisor", sandbox.get("spec", "podTemplate", "spec", "runtimeClassName"));
        assertEquals(Map.of("node-pool", "agents"), sandbox.get("spec", "podTemplate", "spec", "nodeSelector"));
    }

    @Test
    void sandboxAndStatefulSetDescribeTheSamePod() {
        var set = KubernetesRuntime.statefulSetFor(spec("smithy-o-r-1"), config(300));
        var sandbox = SandboxRuntime.sandboxFor(spec("smithy-o-r-1"), config(300), Instant.EPOCH);

        assertEquals(
            io.fabric8.kubernetes.client.utils.Serialization.asJson(set.getSpec().getTemplate().getSpec()),
            io.fabric8.kubernetes.client.utils.Serialization.asJson(
                sandbox.<Map<String, Object>>get("spec", "podTemplate", "spec")
            )
        );
    }

    @Test
    void readyConditionIsReadFromStatus() {
        var sandbox = SandboxRuntime.sandboxFor(spec("smithy-o-r-1"), config(300), Instant.EPOCH);
        assertEquals("Ready: no status yet", SandboxRuntime.readyCondition(sandbox));

        sandbox.getAdditionalProperties().put("status", Map.of("conditions", List.of()));
        assertEquals("Ready: no condition yet", SandboxRuntime.readyCondition(sandbox));

        sandbox
            .getAdditionalProperties()
            .put(
                "status",
                Map.of(
                    "conditions",
                    List.of(
                        Map.of(
                            "type",
                            "Ready",
                            "status",
                            "False",
                            "reason",
                            "DependenciesNotReady",
                            "message",
                            "pod pending"
                        )
                    )
                )
            );
        assertEquals("Ready=False DependenciesNotReady: pod pending", SandboxRuntime.readyCondition(sandbox));
    }

    // ── Against the API ──────────────────────────────────────

    @Test
    void createMakesTheCacheClaimOnceAndTheSandbox() {
        String id = KubernetesNames.objectName("smithy-o-r-1");
        runningPod(id);

        runtime.create(spec("smithy-o-r-1"));

        GenericKubernetesResource created = sandbox(id);
        assertNotNull(created);
        assertEquals("Running", created.get("spec", "operatingMode"));
        assertNotNull(client.persistentVolumeClaims().inNamespace(NS).withName("smithy-cache-npm").get());
        assertTrue(runtime.exists("smithy-o-r-1"));
        assertEquals(Optional.of(true), runtime.isRunning("smithy-o-r-1"));

        var duplicate = assertThrows(RuntimeException.class, () -> runtime.create(spec("smithy-o-r-1")));
        assertTrue(duplicate.getMessage().contains("already exists"));
    }

    @Test
    void createFailsWhenThePodNeverRunsAndSaysWhatTheSandboxReports() {
        var error = assertThrows(RuntimeException.class, () ->
            new SandboxRuntime(client, config(1)).create(spec("never-runs"))
        );

        assertTrue(error.getMessage().contains("pod not running after 1s"), error.getMessage());
        assertTrue(error.getMessage().contains("Ready: no status yet"), error.getMessage());
        assertTrue(error.getMessage().contains("pod: No pod."), error.getMessage());
    }

    @Test
    void listingReturnsOriginalNamesOfManagedContainersOnly() {
        String running = "Smithy_o.r-1";
        String stopped = "smithy-o-r-2";
        runningPod(KubernetesNames.objectName(running));
        runtime.create(spec(running));
        client
            .genericKubernetesResources(SandboxRuntime.SANDBOX)
            .inNamespace(NS)
            .resource(SandboxRuntime.sandboxFor(spec(stopped), config(5), Instant.EPOCH))
            .create();
        var unrelated = SandboxRuntime.sandboxFor(spec("unrelated"), config(5), Instant.EPOCH);
        unrelated.getMetadata().setLabels(Map.of("app", "x"));
        client.genericKubernetesResources(SandboxRuntime.SANDBOX).inNamespace(NS).resource(unrelated).create();

        assertEquals(List.of(running), runtime.listManaged(false));
        assertEquals(Set.of(running, stopped), Set.copyOf(runtime.listManaged(true)));
    }

    @Test
    void suspendedSandboxesAreStoppedAndStartResumesThem() {
        String name = "smithy-o-r-3";
        String id = KubernetesNames.objectName(name);
        var suspended = SandboxRuntime.sandboxFor(spec(name), config(5), Instant.EPOCH);
        suspended.<Map<String, Object>>get("spec").put("operatingMode", "Suspended");
        client.genericKubernetesResources(SandboxRuntime.SANDBOX).inNamespace(NS).resource(suspended).create();

        assertEquals(Optional.of(false), runtime.isRunning(name));
        assertEquals(Optional.empty(), runtime.isRunning("missing"));
        assertFalse(runtime.start("missing"));

        // The mock has no controller to bring the pod back; put it there as one would.
        runningPod(id);
        assertTrue(runtime.start(name));
        assertEquals("Running", sandbox(id).get("spec", "operatingMode"));
        assertEquals(Optional.of(true), runtime.isRunning(name));
    }

    @Test
    void aSandboxWhosePodIsGoneIsNotRunningButStillExists() {
        String name = "smithy-o-r-5";
        String id = KubernetesNames.objectName(name);
        runningPod(id);
        runtime.create(spec(name));

        client.pods().inNamespace(NS).withName(id).delete();

        assertTrue(runtime.exists(name));
        assertEquals(Optional.of(false), runtime.isRunning(name));
        assertEquals(List.of(), runtime.listManaged(false));
        assertEquals(List.of(name), runtime.listManaged(true));
    }

    @Test
    void removeDeletesTheSandboxItsPodAndItsVolumeButKeepsCaches() {
        String name = "smithy-o-r-4";
        String id = KubernetesNames.objectName(name);
        runningPod(id);
        runtime.create(spec(name));
        client
            .persistentVolumeClaims()
            .inNamespace(NS)
            .resource(new PersistentVolumeClaimBuilder().withNewMetadata().withName("task-" + id).endMetadata().build())
            .create();

        runtime.remove(name);

        assertNull(sandbox(id));
        assertNull(client.pods().inNamespace(NS).withName(id).get());
        assertNull(client.persistentVolumeClaims().inNamespace(NS).withName("task-" + id).get());
        assertNotNull(client.persistentVolumeClaims().inNamespace(NS).withName("smithy-cache-npm").get());
        assertFalse(runtime.exists(name));
    }

    @Test
    void podAndClaimAreNamedAsTheControllerNamesThem() {
        assertEquals("smithy-o-r-1", SandboxRuntime.podName("smithy-o-r-1"));
        assertEquals("task-smithy-o-r-1", SandboxRuntime.claimName("smithy-o-r-1"));
    }

    // ── Fixtures ─────────────────────────────────────────────

    private GenericKubernetesResource sandbox(String id) {
        return client.genericKubernetesResources(SandboxRuntime.SANDBOX).inNamespace(NS).withName(id).get();
    }

    /** The mock has no Sandbox controller, so the pod it would start is put there up front. */
    private void runningPod(String id) {
        Pod pod = new PodBuilder()
            .withNewMetadata()
            .withName(id)
            .withLabels(Map.of("smithy.managed", "true", TaskPods.ID_LABEL, id))
            .endMetadata()
            .withNewStatus()
            .withPhase("Running")
            .withContainerStatuses(
                new ContainerStatusBuilder()
                    .withName(TaskPods.CONTAINER)
                    .withState(new ContainerStateBuilder().withNewRunning().endRunning().build())
                    .build()
            )
            .endStatus()
            .build();
        client.pods().inNamespace(NS).resource(pod).create();
    }

    private static ContainerSpec spec(String name) {
        var env = new LinkedHashMap<String, String>();
        env.put("CLONE_URL", "http://forgejo.invalid/o/r.git");
        env.put("EMPTY", "");
        return new ContainerSpec(
            name,
            "claude-task:test",
            List.of("smithy-init"),
            Map.of("smithy.managed", "true", "smithy.workflow", "smithy"),
            Map.of("cache-npm", "/root/.npm"),
            env
        );
    }

    private static KubernetesRuntimeConfig config(int startTimeout) {
        return new KubernetesRuntimeConfig(
            NS,
            "claude-task:test",
            null,
            null,
            List.of("npm"),
            null,
            null,
            List.of("ghcr-creds"),
            Map.of("requests", Map.of("memory", "1Gi")),
            startTimeout,
            "sandbox",
            null,
            null,
            null,
            null
        );
    }
}
