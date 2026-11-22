package dev.smithyai.orchestrator.service.docker;

import dev.smithyai.orchestrator.config.RuntimeConfig.KubernetesRuntimeConfig;
import dev.smithyai.orchestrator.service.docker.dto.ContainerSpec;
import dev.smithyai.orchestrator.service.docker.dto.ExecResult;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Task containers as single-replica StatefulSets in one namespace.
 *
 * <p>A StatefulSet rather than a bare pod, because a drained node deletes a bare
 * pod for good and the run with it. Its one volume holds /workspace, /tmp and
 * /root, which is where a task keeps its clone, its state file and its Claude
 * transcripts, so a rescheduled pod picks up where the old one stopped — what a
 * restarted Docker container does with its own filesystem.
 *
 * <p>The pod itself and the way it is reached are shared with
 * {@link SandboxRuntime}: see {@link TaskPods} and {@link PodExec}.
 */
@Slf4j
public class KubernetesRuntime implements ContainerRuntime {

    static final String CONTAINER = TaskPods.CONTAINER;
    static final String VOLUME = TaskPods.VOLUME;
    static final String MANAGED_LABEL = TaskPods.MANAGED_LABEL;
    static final String ID_LABEL = TaskPods.ID_LABEL;
    static final String NAME_ANNOTATION = TaskPods.NAME_ANNOTATION;
    static final String CACHE_LABEL = TaskPods.CACHE_LABEL;

    private static final Duration REMOVE_TIMEOUT = Duration.ofSeconds(120);

    private final KubernetesClient client;
    private final KubernetesRuntimeConfig config;
    private final String namespace;
    private final PodExec pods;

    public KubernetesRuntime(KubernetesClient client, KubernetesRuntimeConfig config) {
        this.client = client;
        this.config = config;
        this.namespace = TaskNamespace.resolve(client, config);
        this.pods = new PodExec(client, namespace);
    }

    // ── Lifecycle ────────────────────────────────────────────

    @Override
    public void create(ContainerSpec spec) {
        String id = KubernetesNames.objectName(spec.name());
        if (statefulSet(id) != null) {
            throw new RuntimeException("Failed to create container " + spec.name() + ": it already exists");
        }
        spec.caches().keySet().forEach(this::ensureCacheClaim);
        client.apps().statefulSets().inNamespace(namespace).resource(statefulSetFor(spec, config)).create();
        if (!awaitRunning(id)) {
            throw new RuntimeException(
                "Failed to start container " +
                    spec.name() +
                    ": pod not running after " +
                    config.startTimeout() +
                    "s. " +
                    podSummary(id)
            );
        }
    }

    @Override
    public boolean exists(String name) {
        return statefulSet(KubernetesNames.objectName(name)) != null;
    }

    @Override
    public Optional<Boolean> isRunning(String name) {
        String id = KubernetesNames.objectName(name);
        try {
            StatefulSet set = statefulSet(id);
            if (set == null) {
                log.warn("No StatefulSet {} for container {}", id, name);
                return Optional.empty();
            }
            if (replicas(set) == 0) return Optional.of(false);
            return Optional.of(isRunning(pod(id)));
        } catch (KubernetesClientException e) {
            log.warn("Failed to inspect container {}: {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean start(String name) {
        String id = KubernetesNames.objectName(name);
        try {
            StatefulSet set = statefulSet(id);
            if (set == null) {
                log.warn("Failed to start container {}: no StatefulSet {}", name, id);
                return false;
            }
            if (replicas(set) == 0) {
                client.apps().statefulSets().inNamespace(namespace).withName(id).scale(1);
            }
            if (!awaitRunning(id)) {
                log.warn("Container {} not running after {}s: {}", name, config.startTimeout(), podSummary(id));
                return false;
            }
            return true;
        } catch (KubernetesClientException e) {
            log.warn("Failed to start container {}: {}", name, e.getMessage());
            return false;
        }
    }

    @Override
    public List<String> listManaged(boolean includeStopped) {
        var sets = client
            .apps()
            .statefulSets()
            .inNamespace(namespace)
            .withLabel(MANAGED_LABEL, "true")
            .list()
            .getItems();
        var running = includeStopped ? null : TaskNamespace.runningIds(client, namespace);
        return sets
            .stream()
            .filter(s -> running == null || running.contains(s.getMetadata().getName()))
            .map(s -> TaskNamespace.originalName(s.getMetadata()))
            .toList();
    }

    @Override
    public void remove(String name) {
        String id = KubernetesNames.objectName(name);
        try {
            client
                .apps()
                .statefulSets()
                .inNamespace(namespace)
                .withName(id)
                .withTimeout(REMOVE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                .delete();
            // The pod goes with the StatefulSet; waiting for it means the claim
            // below is not held, and a new container with this name will not
            // bind to a volume that is still being deleted.
            client
                .pods()
                .inNamespace(namespace)
                .withName(podName(id))
                .withTimeout(REMOVE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                .delete();
            client
                .persistentVolumeClaims()
                .inNamespace(namespace)
                .withName(claimName(id))
                .withTimeout(REMOVE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                .delete();
        } catch (KubernetesClientException e) {
            log.warn("Failed to remove container {}: {}", name, e.getMessage());
        }
    }

    // ── Logs and exec ────────────────────────────────────────

    @Override
    public String logs(String name, int tailLines) {
        return pods.logs(podName(KubernetesNames.objectName(name)), tailLines);
    }

    @Override
    public String ownLogs(int tailLines) {
        return pods.ownLogs(tailLines);
    }

    @Override
    public ExecResult exec(
        String name,
        List<String> command,
        Map<String, String> environment,
        String workdir,
        byte[] stdin,
        Duration timeout
    ) {
        return pods.exec(podName(KubernetesNames.objectName(name)), command, environment, workdir, stdin, timeout);
    }

    @Override
    public byte[] execForBytes(String name, List<String> command, Duration timeout) {
        return pods.execForBytes(podName(KubernetesNames.objectName(name)), command, timeout);
    }

    static List<String> wrap(List<String> command, Map<String, String> environment, String workdir) {
        return PodExec.wrap(command, environment, workdir);
    }

    // ── Manifests ────────────────────────────────────────────

    static StatefulSet statefulSetFor(ContainerSpec spec, KubernetesRuntimeConfig config) {
        String id = KubernetesNames.objectName(spec.name());
        var labels = TaskPods.labels(spec, id);
        var annotations = TaskPods.annotations(spec);

        var claim = new PersistentVolumeClaimBuilder()
            .withNewMetadata()
            .withName(VOLUME)
            .withLabels(Map.of(MANAGED_LABEL, "true", ID_LABEL, id))
            .endMetadata()
            .withSpec(TaskPods.claimSpec(config))
            .build();

        return new StatefulSetBuilder()
            .withNewMetadata()
            .withName(id)
            .withLabels(labels)
            .withAnnotations(annotations)
            .endMetadata()
            .withNewSpec()
            .withReplicas(1)
            .withServiceName(id)
            .withNewSelector()
            .withMatchLabels(Map.of(ID_LABEL, id))
            .endSelector()
            .withNewPersistentVolumeClaimRetentionPolicy()
            .withWhenDeleted("Delete")
            .withWhenScaled("Retain")
            .endPersistentVolumeClaimRetentionPolicy()
            .withVolumeClaimTemplates(claim)
            .withNewTemplate()
            .withNewMetadata()
            .withLabels(labels)
            .withAnnotations(annotations)
            .endMetadata()
            .withSpec(TaskPods.podSpec(spec, config))
            .endTemplate()
            .endSpec()
            .build();
    }

    static PersistentVolumeClaim cacheClaimFor(String cache, KubernetesRuntimeConfig config) {
        return TaskPods.cacheClaimFor(cache, config);
    }

    static String cacheClaimName(String cache) {
        return TaskPods.cacheClaimName(cache);
    }

    static String podName(String id) {
        return id + "-0";
    }

    static String claimName(String id) {
        return VOLUME + "-" + id + "-0";
    }

    // ── Helpers ──────────────────────────────────────────────

    private void ensureCacheClaim(String cache) {
        TaskNamespace.ensureCacheClaim(client, namespace, cache, config);
    }

    private StatefulSet statefulSet(String id) {
        return client.apps().statefulSets().inNamespace(namespace).withName(id).get();
    }

    private Pod pod(String id) {
        return client.pods().inNamespace(namespace).withName(podName(id)).get();
    }

    private boolean awaitRunning(String id) {
        return TaskNamespace.awaitRunning(client, namespace, podName(id), config.startTimeout());
    }

    private String podSummary(String id) {
        return TaskPods.summary(pod(id));
    }

    static boolean isRunning(Pod pod) {
        return TaskPods.isRunning(pod);
    }

    private static int replicas(StatefulSet set) {
        Integer replicas = set.getSpec().getReplicas();
        return replicas == null ? 1 : replicas;
    }
}
