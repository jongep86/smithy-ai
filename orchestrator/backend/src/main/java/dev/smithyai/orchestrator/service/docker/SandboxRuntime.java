package dev.smithyai.orchestrator.service.docker;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.smithyai.orchestrator.config.RuntimeConfig.KubernetesRuntimeConfig;
import dev.smithyai.orchestrator.service.docker.dto.ContainerSpec;
import dev.smithyai.orchestrator.service.docker.dto.ExecResult;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Task containers as {@code agents.x-k8s.io/v1beta1} Sandboxes, the CRD of
 * kubernetes-sigs/agent-sandbox.
 *
 * <p>One Sandbox per task. Its controller does what the StatefulSet does for
 * {@link KubernetesRuntime}: it keeps a pod of the Sandbox's name alive, recreates
 * it after a node drain, and owns the task's volume so that deleting the Sandbox
 * deletes everything. On top of that it offers a stopped state that keeps the
 * volume ({@code operatingMode: Suspended}) and, when configured, an expiry that
 * reaps a task the orchestrator forgot ({@code shutdownTime}).
 *
 * <p>No warm pool: a task's pod carries the run's repository and tokens as
 * environment, and the controller cold-starts a Sandbox that asks for either.
 * No Sandbox-side exec either: the pod is reached with {@code pods/exec}, as
 * before, through {@link PodExec}.
 *
 * <p>fabric8 has no model for the CRD, so the manifest is a
 * {@link GenericKubernetesResource}: a map whose {@code podTemplate.spec} is
 * the typed {@link io.fabric8.kubernetes.api.model.PodSpec} both runtimes share,
 * serialised. Readiness is read from {@code status.conditions[type=Ready]}; the
 * CRD has no {@code status.phase}.
 */
@Slf4j
public class SandboxRuntime implements ContainerRuntime {

    static final String GROUP = "agents.x-k8s.io";
    static final String VERSION = "v1beta1";
    static final String KIND = "Sandbox";
    static final String PLURAL = "sandboxes";
    static final ResourceDefinitionContext SANDBOX = new ResourceDefinitionContext.Builder()
        .withGroup(GROUP)
        .withVersion(VERSION)
        .withKind(KIND)
        .withPlural(PLURAL)
        .withNamespaced(true)
        .build();

    static final String RUNNING = "Running";
    static final String SUSPENDED = "Suspended";

    private static final Duration REMOVE_TIMEOUT = Duration.ofSeconds(120);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final KubernetesClient client;
    private final KubernetesRuntimeConfig config;
    private final String namespace;
    private final PodExec pods;

    public SandboxRuntime(KubernetesClient client, KubernetesRuntimeConfig config) {
        this.client = client;
        this.config = config;
        this.namespace = TaskNamespace.resolve(client, config);
        this.pods = new PodExec(client, namespace);
    }

    // ── Lifecycle ────────────────────────────────────────────

    @Override
    public void create(ContainerSpec spec) {
        String id = KubernetesNames.objectName(spec.name());
        if (sandbox(id) != null) {
            throw new RuntimeException("Failed to create container " + spec.name() + ": it already exists");
        }
        spec
            .caches()
            .keySet()
            .forEach(cache -> TaskNamespace.ensureCacheClaim(client, namespace, cache, config));
        sandboxes().resource(sandboxFor(spec, config, Instant.now())).create();
        if (!awaitRunning(id)) {
            throw new RuntimeException(
                "Failed to start container " +
                    spec.name() +
                    ": pod not running after " +
                    config.startTimeout() +
                    "s. " +
                    summary(id)
            );
        }
    }

    @Override
    public boolean exists(String name) {
        return sandbox(KubernetesNames.objectName(name)) != null;
    }

    @Override
    public Optional<Boolean> isRunning(String name) {
        String id = KubernetesNames.objectName(name);
        try {
            GenericKubernetesResource sandbox = sandbox(id);
            if (sandbox == null) {
                log.warn("No Sandbox {} for container {}", id, name);
                return Optional.empty();
            }
            if (SUSPENDED.equals(operatingMode(sandbox))) return Optional.of(false);
            return Optional.of(TaskPods.isRunning(pod(id)));
        } catch (KubernetesClientException e) {
            log.warn("Failed to inspect container {}: {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean start(String name) {
        String id = KubernetesNames.objectName(name);
        try {
            GenericKubernetesResource sandbox = sandbox(id);
            if (sandbox == null) {
                log.warn("Failed to start container {}: no Sandbox {}", name, id);
                return false;
            }
            if (SUSPENDED.equals(operatingMode(sandbox))) {
                sandboxes()
                    .withName(id)
                    .edit(s -> {
                        spec(s).put("operatingMode", RUNNING);
                        return s;
                    });
            }
            if (!awaitRunning(id)) {
                log.warn("Container {} not running after {}s: {}", name, config.startTimeout(), summary(id));
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
        var all = sandboxes().withLabel(TaskPods.MANAGED_LABEL, "true").list().getItems();
        var running = includeStopped ? null : TaskNamespace.runningIds(client, namespace);
        return all
            .stream()
            .filter(s -> running == null || running.contains(s.getMetadata().getName()))
            .map(s -> TaskNamespace.originalName(s.getMetadata()))
            .toList();
    }

    @Override
    public void remove(String name) {
        String id = KubernetesNames.objectName(name);
        try {
            // Foreground: the Sandbox stays until the controller's owner
            // references have taken the pod and the claim with it.
            sandboxes()
                .withName(id)
                .withPropagationPolicy(DeletionPropagation.FOREGROUND)
                .withTimeout(REMOVE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                .delete();
            // Belt and braces, for a controller that was not there to do it.
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

    // ── Manifest ─────────────────────────────────────────────

    /**
     * The Sandbox for a task. {@code now} is what an expiry counts from.
     *
     * <p>Only fields every v1beta1 controller knows are always sent;
     * {@code failedPodPolicy} (v1.0.12) goes in only when configured, so an
     * older controller is not handed a field it would reject.
     */
    static GenericKubernetesResource sandboxFor(ContainerSpec spec, KubernetesRuntimeConfig config, Instant now) {
        String id = KubernetesNames.objectName(spec.name());
        var labels = TaskPods.labels(spec, id);
        var annotations = TaskPods.annotations(spec);

        var claim = new LinkedHashMap<String, Object>();
        claim.put(
            "metadata",
            Map.of("name", TaskPods.VOLUME, "labels", Map.of(TaskPods.MANAGED_LABEL, "true", TaskPods.ID_LABEL, id))
        );
        claim.put("spec", toMap(TaskPods.claimSpec(config)));

        var podTemplate = new LinkedHashMap<String, Object>();
        podTemplate.put("metadata", Map.of("labels", labels, "annotations", annotations));
        podTemplate.put("spec", toMap(TaskPods.podSpec(spec, config)));

        var sandboxSpec = new LinkedHashMap<String, Object>();
        sandboxSpec.put("service", false);
        sandboxSpec.put("operatingMode", RUNNING);
        sandboxSpec.put("volumeClaimTemplates", List.of(claim));
        sandboxSpec.put("podTemplate", podTemplate);
        if (config.maxLifetime() != null) {
            Instant shutdown = now.plusSeconds(config.maxLifetime()).truncatedTo(ChronoUnit.SECONDS);
            sandboxSpec.put("shutdownTime", DateTimeFormatter.ISO_INSTANT.format(shutdown));
            sandboxSpec.put("shutdownPolicy", "Delete");
        }
        if (config.failedPodPolicy() != null) {
            sandboxSpec.put("failedPodPolicy", config.failedPodPolicy());
        }

        return new GenericKubernetesResourceBuilder()
            .withApiVersion(GROUP + "/" + VERSION)
            .withKind(KIND)
            .withNewMetadata()
            .withName(id)
            .withLabels(labels)
            .withAnnotations(annotations)
            .endMetadata()
            .addToAdditionalProperties("spec", sandboxSpec)
            .build();
    }

    /** The pod a Sandbox runs has the Sandbox's name. */
    static String podName(String id) {
        return id;
    }

    /** The controller names a claim {@code <template name>-<sandbox name>}. */
    static String claimName(String id) {
        return TaskPods.VOLUME + "-" + id;
    }

    static String operatingMode(GenericKubernetesResource sandbox) {
        Object mode = spec(sandbox).get("operatingMode");
        return mode == null ? RUNNING : mode.toString();
    }

    /** The {@code Ready} condition as {@code status=reason: message}, or why there is none. */
    static String readyCondition(GenericKubernetesResource sandbox) {
        Object status = sandbox.getAdditionalProperties().get("status");
        if (!(status instanceof Map<?, ?> statusMap) || !(statusMap.get("conditions") instanceof List<?> conditions)) {
            return "Ready: no status yet";
        }
        for (Object condition : conditions) {
            if (condition instanceof Map<?, ?> c && "Ready".equals(c.get("type"))) {
                String text = "Ready=" + c.get("status") + " " + c.get("reason");
                return c.get("message") == null ? text : text + ": " + c.get("message");
            }
        }
        return "Ready: no condition yet";
    }

    // ── Helpers ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> spec(GenericKubernetesResource sandbox) {
        return (Map<String, Object>) sandbox
            .getAdditionalProperties()
            .computeIfAbsent("spec", k -> new LinkedHashMap<String, Object>());
    }

    private static Map<String, Object> toMap(Object typed) {
        return Serialization.jsonMapper().convertValue(typed, MAP);
    }

    private NonNamespaceOperation<
        GenericKubernetesResource,
        GenericKubernetesResourceList,
        Resource<GenericKubernetesResource>
    > sandboxes() {
        return client.genericKubernetesResources(SANDBOX).inNamespace(namespace);
    }

    private GenericKubernetesResource sandbox(String id) {
        return sandboxes().withName(id).get();
    }

    private Pod pod(String id) {
        return client.pods().inNamespace(namespace).withName(podName(id)).get();
    }

    private boolean awaitRunning(String id) {
        return TaskNamespace.awaitRunning(client, namespace, podName(id), config.startTimeout());
    }

    /** The Sandbox's Ready condition and the pod's state, for an error message. */
    private String summary(String id) {
        GenericKubernetesResource sandbox = sandbox(id);
        String ready = sandbox == null ? "Sandbox gone" : readyCondition(sandbox);
        return ready + "; pod: " + TaskPods.summary(pod(id));
    }
}
