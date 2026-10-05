package dev.smithyai.orchestrator.service.docker;

import dev.smithyai.orchestrator.config.RuntimeConfig.KubernetesRuntimeConfig;
import dev.smithyai.orchestrator.service.docker.dto.ContainerSpec;
import dev.smithyai.orchestrator.service.docker.dto.ExecResult;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.kubernetes.client.dsl.TtyExecOutputErrorable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;

/**
 * Task containers as single-replica StatefulSets in one namespace.
 *
 * <p>A StatefulSet rather than a bare pod, because a drained node deletes a bare
 * pod for good and the run with it. Its one volume holds /workspace, /tmp and
 * /root, which is where a task keeps its clone, its state file and its Claude
 * transcripts, so a rescheduled pod picks up where the old one stopped — what a
 * restarted Docker container does with its own filesystem.
 */
@Slf4j
public class KubernetesRuntime implements ContainerRuntime {

    static final String CONTAINER = "task";
    static final String VOLUME = "task";
    static final String MANAGED_LABEL = "smithy.managed";
    static final String ID_LABEL = "smithy.ai/container-id";
    static final String NAME_ANNOTATION = "smithy.ai/container-name";
    static final String CACHE_LABEL = "smithy.ai/cache";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REMOVE_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration STREAM_DRAIN = Duration.ofSeconds(5);

    /** Seeds /root from the image once, so images that install tools there keep them. */
    private static final String SEED_ROOT_SCRIPT = """
        mkdir -p /seed/root /seed/workspace /seed/tmp && chmod 1777 /seed/tmp
        if [ ! -e /seed/root/.smithy-seeded ]; then
          cp -a /root/. /seed/root/ && touch /seed/root/.smithy-seeded
        fi
        """;

    private final KubernetesClient client;
    private final KubernetesRuntimeConfig config;
    private final String namespace;

    public KubernetesRuntime(KubernetesClient client, KubernetesRuntimeConfig config) {
        this.client = client;
        this.config = config;
        this.namespace =
            config.namespace() != null && !config.namespace().isBlank() ? config.namespace() : client.getNamespace();
        if (this.namespace == null || this.namespace.isBlank()) {
            throw new IllegalStateException("runtime.kubernetes.namespace is required outside a cluster");
        }
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
        var running = includeStopped
            ? null
            : client
                  .pods()
                  .inNamespace(namespace)
                  .withLabel(MANAGED_LABEL, "true")
                  .list()
                  .getItems()
                  .stream()
                  .filter(KubernetesRuntime::isRunning)
                  .map(p -> p.getMetadata().getLabels().get(ID_LABEL))
                  .toList();
        return sets
            .stream()
            .filter(s -> running == null || running.contains(s.getMetadata().getName()))
            .map(KubernetesRuntime::originalName)
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

    // ── Logs ─────────────────────────────────────────────────

    @Override
    public String logs(String name, int tailLines) {
        String id = KubernetesNames.objectName(name);
        try {
            return client
                .pods()
                .inNamespace(namespace)
                .withName(podName(id))
                .inContainer(CONTAINER)
                .tailingLines(tailLines)
                .getLog();
        } catch (KubernetesClientException e) {
            return "Failed to read logs of " + name + ": " + e.getMessage();
        }
    }

    @Override
    public String ownLogs(int tailLines) {
        String self = System.getenv("HOSTNAME");
        if (self == null || self.isBlank()) {
            return "Unable to determine own pod name (HOSTNAME not set)";
        }
        String ownNamespace = System.getenv().getOrDefault("POD_NAMESPACE", namespace);
        try {
            var pod = client.pods().inNamespace(ownNamespace).withName(self);
            Pod current = pod.get();
            if (current == null) {
                return "Own pod " + ownNamespace + "/" + self + " not found";
            }
            var containers = current.getSpec().getContainers();
            String container =
                containers.size() == 1
                    ? containers.getFirst().getName()
                    : System.getenv().getOrDefault("SMITHY_CONTAINER_NAME", "orchestrator");
            return pod.inContainer(container).tailingLines(tailLines).getLog();
        } catch (KubernetesClientException e) {
            return "Failed to read own logs: " + e.getMessage();
        }
    }

    // ── Exec ─────────────────────────────────────────────────

    @Override
    public ExecResult exec(
        String name,
        List<String> command,
        Map<String, String> environment,
        String workdir,
        byte[] stdin,
        Duration timeout
    ) {
        var raw = run(name, wrap(command, environment, workdir), stdin, timeout);
        return new ExecResult(
            raw.exitCode,
            new String(raw.stdout, StandardCharsets.UTF_8),
            new String(raw.stderr, StandardCharsets.UTF_8)
        );
    }

    @Override
    public byte[] execForBytes(String name, List<String> command, Duration timeout) {
        var raw = run(name, command, null, timeout);
        if (raw.exitCode != 0) {
            throw new RuntimeException(
                "exec failed (exit " +
                    raw.exitCode +
                    ") in " +
                    name +
                    ": " +
                    command +
                    "\nstderr: " +
                    new String(raw.stderr, StandardCharsets.UTF_8)
            );
        }
        return raw.stdout;
    }

    /**
     * Kubernetes exec has no working-directory or environment flags. Both go in
     * as positional arguments to a fixed script, so nothing a caller passes is
     * ever interpolated into shell source.
     */
    static List<String> wrap(List<String> command, Map<String, String> environment, String workdir) {
        var env = new ArrayList<String>();
        if (environment != null) {
            environment.forEach((k, v) -> env.add(k + "=" + v));
        }
        var wrapped = new ArrayList<String>();
        if (workdir != null) {
            wrapped.addAll(List.of("sh", "-c", "cd \"$0\" && exec \"$@\"", workdir));
        }
        if (!env.isEmpty()) {
            wrapped.add("env");
            wrapped.addAll(env);
        }
        wrapped.addAll(command);
        return wrapped;
    }

    private record RawResult(int exitCode, byte[] stdout, byte[] stderr) {}

    private RawResult run(String name, List<String> command, byte[] stdin, Duration timeout) {
        String id = KubernetesNames.objectName(name);
        Duration effective = timeout != null ? timeout : DEFAULT_TIMEOUT;
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var closed = new CountDownLatch(1);

        var target = client.pods().inNamespace(namespace).withName(podName(id)).inContainer(CONTAINER);
        TtyExecOutputErrorable io = stdin != null ? target.redirectingInput() : target;
        var container = io
            .writingOutput(out)
            .writingError(err)
            .usingListener(
                new ExecListener() {
                    @Override
                    public void onClose(int code, String reason) {
                        closed.countDown();
                    }

                    @Override
                    public void onFailure(Throwable t, Response failureResponse) {
                        closed.countDown();
                    }
                }
            );

        ExecWatch watch = null;
        try {
            watch = container.exec(command.toArray(String[]::new));
            if (stdin != null) {
                try (var input = watch.getInput()) {
                    input.write(stdin);
                    input.flush();
                }
            }
            Integer code = watch.exitCode().get(effective.toMillis(), TimeUnit.MILLISECONDS);
            // The status frame can arrive before the last output frames.
            closed.await(STREAM_DRAIN.toMillis(), TimeUnit.MILLISECONDS);
            return new RawResult(code == null ? 1 : code, out.toByteArray(), err.toByteArray());
        } catch (TimeoutException e) {
            return new RawResult(
                124,
                out.toByteArray(),
                ("Timed out after " + effective).getBytes(StandardCharsets.UTF_8)
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RawResult(130, out.toByteArray(), "Interrupted".getBytes(StandardCharsets.UTF_8));
        } catch (ExecutionException | IOException | KubernetesClientException e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            return new RawResult(1, out.toByteArray(), describe(cause).getBytes(StandardCharsets.UTF_8));
        } finally {
            if (watch != null) watch.close();
        }
    }

    private static String describe(Throwable t) {
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    // ── Manifests ────────────────────────────────────────────

    static StatefulSet statefulSetFor(ContainerSpec spec, KubernetesRuntimeConfig config) {
        String id = KubernetesNames.objectName(spec.name());

        var labels = new LinkedHashMap<String, String>();
        spec
            .labels()
            .forEach((k, v) -> {
                String value = KubernetesNames.labelValue(v);
                if (!value.isEmpty()) labels.put(k, value);
            });
        labels.put(ID_LABEL, id);
        var annotations = Map.of(NAME_ANNOTATION, spec.name());

        var env = new ArrayList<EnvVar>();
        spec.environment().forEach((k, v) -> env.add(new EnvVar(k, v == null ? "null" : v, null)));

        var mounts = new ArrayList<VolumeMount>(
            List.of(
                mount(VOLUME, "/workspace", "workspace"),
                mount(VOLUME, "/tmp", "tmp"),
                mount(VOLUME, "/root", "root")
            )
        );
        var volumes = new ArrayList<Volume>();
        spec
            .caches()
            .forEach((cache, path) -> {
                mounts.add(mount(cache, path, null));
                volumes.add(
                    new VolumeBuilder()
                        .withName(cache)
                        .withNewPersistentVolumeClaim()
                        .withClaimName(cacheClaimName(cache))
                        .endPersistentVolumeClaim()
                        .build()
                );
            });

        var resources = new ResourceRequirementsBuilder();
        config
            .resources()
            .forEach((kind, values) -> {
                var quantities = new LinkedHashMap<String, Quantity>();
                values.forEach((resource, amount) -> quantities.put(resource, new Quantity(amount)));
                if ("requests".equals(kind)) resources.withRequests(quantities);
                if ("limits".equals(kind)) resources.withLimits(quantities);
            });

        var claim = new PersistentVolumeClaimBuilder()
            .withNewMetadata()
            .withName(VOLUME)
            .withLabels(Map.of(MANAGED_LABEL, "true", ID_LABEL, id))
            .endMetadata()
            .withNewSpec()
            .withAccessModes("ReadWriteOnce")
            .withStorageClassName(config.storageClass())
            .withNewResources()
            .withRequests(Map.of("storage", new Quantity(config.volumeSize())))
            .endResources()
            .endSpec()
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
            .withNewSpec()
            .withAutomountServiceAccountToken(false)
            .withEnableServiceLinks(false)
            // smithy-init ends in `sleep infinity`, which ignores SIGTERM as PID 1.
            .withTerminationGracePeriodSeconds(5L)
            .withImagePullSecrets(config.imagePullSecrets().stream().map(LocalObjectReference::new).toList())
            .addNewInitContainer()
            .withName("seed-root")
            .withImage(spec.image())
            .withCommand("sh", "-c", SEED_ROOT_SCRIPT)
            .withVolumeMounts(mount(VOLUME, "/seed", null))
            .endInitContainer()
            .addNewContainer()
            .withName(CONTAINER)
            .withImage(spec.image())
            .withArgs(spec.command())
            .withWorkingDir("/workspace")
            .withEnv(env)
            .withVolumeMounts(mounts)
            .withResources(resources.build())
            .endContainer()
            .withVolumes(volumes)
            .endSpec()
            .endTemplate()
            .endSpec()
            .build();
    }

    static PersistentVolumeClaim cacheClaimFor(String cache, KubernetesRuntimeConfig config) {
        return new PersistentVolumeClaimBuilder()
            .withNewMetadata()
            .withName(cacheClaimName(cache))
            .withLabels(Map.of(CACHE_LABEL, "true"))
            .endMetadata()
            .withNewSpec()
            .withAccessModes(config.cacheAccessMode())
            .withStorageClassName(config.storageClass())
            .withNewResources()
            .withRequests(Map.of("storage", new Quantity(config.cacheSize())))
            .endResources()
            .endSpec()
            .build();
    }

    static String cacheClaimName(String cache) {
        return "smithy-" + cache;
    }

    static String podName(String id) {
        return id + "-0";
    }

    static String claimName(String id) {
        return VOLUME + "-" + id + "-0";
    }

    private static VolumeMount mount(String volume, String path, String subPath) {
        return new VolumeMountBuilder().withName(volume).withMountPath(path).withSubPath(subPath).build();
    }

    // ── Helpers ──────────────────────────────────────────────

    private void ensureCacheClaim(String cache) {
        var claims = client.persistentVolumeClaims().inNamespace(namespace);
        if (claims.withName(cacheClaimName(cache)).get() == null) {
            claims.resource(cacheClaimFor(cache, config)).create();
            log.info("Created cache claim {}", cacheClaimName(cache));
        }
    }

    private StatefulSet statefulSet(String id) {
        return client.apps().statefulSets().inNamespace(namespace).withName(id).get();
    }

    private Pod pod(String id) {
        return client.pods().inNamespace(namespace).withName(podName(id)).get();
    }

    private boolean awaitRunning(String id) {
        try {
            client
                .pods()
                .inNamespace(namespace)
                .withName(podName(id))
                .waitUntilCondition(KubernetesRuntime::isRunning, config.startTimeout(), TimeUnit.SECONDS);
            return true;
        } catch (KubernetesClientException e) {
            return false;
        }
    }

    private String podSummary(String id) {
        Pod pod = pod(id);
        if (pod == null || pod.getStatus() == null) return "No pod.";
        var status = pod.getStatus();
        var parts = new ArrayList<String>();
        parts.add("phase=" + status.getPhase());
        if (status.getContainerStatuses() != null) {
            status
                .getContainerStatuses()
                .forEach(c -> {
                    if (c.getState() != null && c.getState().getWaiting() != null) {
                        parts.add(c.getName() + " waiting: " + c.getState().getWaiting().getReason());
                    }
                });
        }
        if (status.getConditions() != null) {
            status
                .getConditions()
                .stream()
                .filter(c -> "False".equals(c.getStatus()) && c.getMessage() != null)
                .forEach(c -> parts.add(c.getType() + ": " + c.getMessage()));
        }
        return String.join("; ", parts);
    }

    static boolean isRunning(Pod pod) {
        if (pod == null || pod.getStatus() == null || pod.getMetadata().getDeletionTimestamp() != null) {
            return false;
        }
        if (!"Running".equals(pod.getStatus().getPhase()) || pod.getStatus().getContainerStatuses() == null) {
            return false;
        }
        return pod
            .getStatus()
            .getContainerStatuses()
            .stream()
            .anyMatch(c -> CONTAINER.equals(c.getName()) && c.getState() != null && c.getState().getRunning() != null);
    }

    private static int replicas(StatefulSet set) {
        Integer replicas = set.getSpec().getReplicas();
        return replicas == null ? 1 : replicas;
    }

    private static String originalName(StatefulSet set) {
        var annotations = set.getMetadata().getAnnotations();
        String original = annotations == null ? null : annotations.get(NAME_ANNOTATION);
        return original != null ? original : set.getMetadata().getName();
    }
}
