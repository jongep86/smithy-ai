package dev.smithyai.orchestrator.service.docker;

import static org.junit.jupiter.api.Assertions.*;

import dev.smithyai.orchestrator.config.RuntimeConfig.KubernetesRuntimeConfig;
import dev.smithyai.orchestrator.service.docker.dto.ContainerSpec;
import io.fabric8.kubernetes.api.model.ContainerStateBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true, https = false)
class KubernetesRuntimeTest {

    private static final String NS = "smithy-tasks";

    KubernetesClient client;
    private KubernetesRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = new KubernetesRuntime(client, config(5));
    }

    // ── Manifest ─────────────────────────────────────────────

    @Test
    void statefulSetCarriesWhatATaskContainerNeeds() {
        var set = KubernetesRuntime.statefulSetFor(spec("Smithy_o.r-1"), config(300));
        String id = set.getMetadata().getName();
        var pod = set.getSpec().getTemplate().getSpec();
        var task = pod.getContainers().getFirst();

        assertEquals(KubernetesNames.objectName("Smithy_o.r-1"), id);
        assertEquals("Smithy_o.r-1", set.getMetadata().getAnnotations().get(KubernetesRuntime.NAME_ANNOTATION));
        assertEquals(1, set.getSpec().getReplicas());
        assertEquals(Map.of(KubernetesRuntime.ID_LABEL, id), set.getSpec().getSelector().getMatchLabels());
        assertEquals("true", set.getSpec().getTemplate().getMetadata().getLabels().get("smithy.managed"));
        assertEquals("smithy", set.getSpec().getTemplate().getMetadata().getLabels().get("smithy.workflow"));
        assertEquals("Delete", set.getSpec().getPersistentVolumeClaimRetentionPolicy().getWhenDeleted());

        assertFalse(pod.getAutomountServiceAccountToken(), "task pods must not get a service account token");
        assertEquals(
            List.of("ghcr-creds"),
            pod
                .getImagePullSecrets()
                .stream()
                .map(r -> r.getName())
                .toList()
        );
        assertEquals(List.of("smithy-init"), task.getArgs());
        assertTrue(
            task.getCommand() == null || task.getCommand().isEmpty(),
            "the image entrypoint stays, as with docker create <image> smithy-init"
        );
        assertEquals("1Gi", task.getResources().getRequests().get("memory").toString());
        assertEquals(
            Map.of("CLONE_URL", "http://forgejo.invalid/o/r.git", "EMPTY", ""),
            task.getEnv().stream().collect(Collectors.toMap(e -> e.getName(), e -> e.getValue()))
        );
        assertEquals(
            Map.of(
                "/workspace",
                "task:workspace",
                "/tmp",
                "task:tmp",
                "/root",
                "task:root",
                "/root/.npm",
                "cache-npm:"
            ),
            task
                .getVolumeMounts()
                .stream()
                .collect(
                    Collectors.toMap(VolumeMount::getMountPath, m -> m.getName() + ":" + nullToEmpty(m.getSubPath()))
                )
        );
        assertEquals("smithy-cache-npm", pod.getVolumes().getFirst().getPersistentVolumeClaim().getClaimName());
        assertEquals("seed-root", pod.getInitContainers().getFirst().getName());
        assertEquals(
            "10Gi",
            set
                .getSpec()
                .getVolumeClaimTemplates()
                .getFirst()
                .getSpec()
                .getResources()
                .getRequests()
                .get("storage")
                .toString()
        );
    }

    @Test
    void execWrapsWorkdirAndEnvironmentAsArguments() {
        var env = new LinkedHashMap<String, String>();
        env.put("A", "1");
        env.put("B", "it's");

        assertEquals(
            List.of("sh", "-c", "cd \"$0\" && exec \"$@\"", "/workspace", "env", "A=1", "B=it's", "git", "status"),
            KubernetesRuntime.wrap(List.of("git", "status"), env, "/workspace")
        );
        assertEquals(List.of("cat", "/tmp/x"), KubernetesRuntime.wrap(List.of("cat", "/tmp/x"), null, null));
        assertEquals(List.of("env", "A=1", "B=it's", "ls"), KubernetesRuntime.wrap(List.of("ls"), env, null));
    }

    // ── Against the API ──────────────────────────────────────

    @Test
    void createMakesTheCacheClaimOnceAndTheTaskStatefulSet() {
        String id = KubernetesNames.objectName("smithy-o-r-1");
        runningPod(id);

        runtime.create(spec("smithy-o-r-1"));

        assertNotNull(client.apps().statefulSets().inNamespace(NS).withName(id).get());
        assertNotNull(client.persistentVolumeClaims().inNamespace(NS).withName("smithy-cache-npm").get());
        assertTrue(runtime.exists("smithy-o-r-1"));
        assertEquals(java.util.Optional.of(true), runtime.isRunning("smithy-o-r-1"));

        var duplicate = assertThrows(RuntimeException.class, () -> runtime.create(spec("smithy-o-r-1")));
        assertTrue(duplicate.getMessage().contains("already exists"));
    }

    @Test
    void createFailsWhenThePodNeverRuns() {
        var error = assertThrows(RuntimeException.class, () ->
            new KubernetesRuntime(client, config(1)).create(spec("never-runs"))
        );

        assertTrue(error.getMessage().contains("pod not running"), error.getMessage());
    }

    @Test
    void listingReturnsOriginalNamesOfManagedContainersOnly() {
        String running = "Smithy_o.r-1";
        String stopped = "smithy-o-r-2";
        runningPod(KubernetesNames.objectName(running));
        runtime.create(spec(running));
        client
            .apps()
            .statefulSets()
            .inNamespace(NS)
            .resource(KubernetesRuntime.statefulSetFor(spec(stopped), config(5)))
            .create();
        client
            .pods()
            .inNamespace(NS)
            .resource(
                new PodBuilder()
                    .withNewMetadata()
                    .withName("unrelated")
                    .withLabels(Map.of("app", "x"))
                    .endMetadata()
                    .build()
            )
            .create();

        assertEquals(List.of(running), runtime.listManaged(false));
        assertEquals(java.util.Set.of(running, stopped), java.util.Set.copyOf(runtime.listManaged(true)));
        assertFalse(runtime.exists("unrelated"));
    }

    @Test
    void stoppedContainersAreNotRunningAndUnknownOnesCannotBeInspected() {
        String name = "smithy-o-r-3";
        var set = KubernetesRuntime.statefulSetFor(spec(name), config(5));
        set.getSpec().setReplicas(0);
        client.apps().statefulSets().inNamespace(NS).resource(set).create();

        assertEquals(java.util.Optional.of(false), runtime.isRunning(name));
        assertEquals(java.util.Optional.empty(), runtime.isRunning("missing"));
        assertFalse(runtime.start("missing"));
    }

    @Test
    void removeDeletesTheTaskAndItsVolumeButKeepsCaches() {
        String name = "smithy-o-r-4";
        String id = KubernetesNames.objectName(name);
        runningPod(id);
        runtime.create(spec(name));
        client
            .persistentVolumeClaims()
            .inNamespace(NS)
            .resource(
                new io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder()
                    .withNewMetadata()
                    .withName(KubernetesRuntime.claimName(id))
                    .endMetadata()
                    .build()
            )
            .create();

        runtime.remove(name);

        assertNull(client.apps().statefulSets().inNamespace(NS).withName(id).get());
        assertNull(client.pods().inNamespace(NS).withName(KubernetesRuntime.podName(id)).get());
        assertNull(client.persistentVolumeClaims().inNamespace(NS).withName(KubernetesRuntime.claimName(id)).get());
        assertNotNull(client.persistentVolumeClaims().inNamespace(NS).withName("smithy-cache-npm").get());
        assertFalse(runtime.exists(name));
    }

    // ── Fixtures ─────────────────────────────────────────────

    /** The mock has no StatefulSet controller, so the pod it would start is put there up front. */
    private void runningPod(String id) {
        Pod pod = new PodBuilder()
            .withNewMetadata()
            .withName(KubernetesRuntime.podName(id))
            .withLabels(Map.of("smithy.managed", "true", KubernetesRuntime.ID_LABEL, id))
            .endMetadata()
            .withNewStatus()
            .withPhase("Running")
            .withContainerStatuses(
                new ContainerStatusBuilder()
                    .withName(KubernetesRuntime.CONTAINER)
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
            startTimeout
        );
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
