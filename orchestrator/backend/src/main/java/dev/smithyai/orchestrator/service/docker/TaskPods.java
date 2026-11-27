package dev.smithyai.orchestrator.service.docker;

import dev.smithyai.orchestrator.config.RuntimeConfig.KubernetesRuntimeConfig;
import dev.smithyai.orchestrator.service.docker.dto.ContainerSpec;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimSpec;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimSpecBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pod a task container is, independent of what owns it.
 *
 * <p>A StatefulSet template and a Sandbox {@code podTemplate} describe the same
 * pod: the task image running {@code smithy-init}, one volume mounted three
 * times with {@code subPath} for /workspace, /tmp and /root, an init container
 * that seeds /root from the image, the shared cache claims, no service-account
 * token. Built here once so the two runtimes cannot drift apart.
 */
final class TaskPods {

    static final String CONTAINER = PodExec.CONTAINER;
    static final String VOLUME = "task";
    static final String MANAGED_LABEL = "smithy.managed";
    static final String ID_LABEL = "smithy.ai/container-id";
    static final String NAME_ANNOTATION = "smithy.ai/container-name";
    static final String CACHE_LABEL = "smithy.ai/cache";

    /** Seeds /root from the image once, so images that install tools there keep them. */
    private static final String SEED_ROOT_SCRIPT = """
        mkdir -p /seed/root /seed/workspace /seed/tmp && chmod 1777 /seed/tmp
        if [ ! -e /seed/root/.smithy-seeded ]; then
          cp -a /root/. /seed/root/ && touch /seed/root/.smithy-seeded
        fi
        """;

    private TaskPods() {}

    /** Labels for the owning object and its pod: the spec's, cleaned, plus the id. */
    static Map<String, String> labels(ContainerSpec spec, String id) {
        var labels = new LinkedHashMap<String, String>();
        spec
            .labels()
            .forEach((k, v) -> {
                String value = KubernetesNames.labelValue(v);
                if (!value.isEmpty()) labels.put(k, value);
            });
        labels.put(ID_LABEL, id);
        return labels;
    }

    static Map<String, String> annotations(ContainerSpec spec) {
        return Map.of(NAME_ANNOTATION, spec.name());
    }

    /** The pod spec both runtimes put in their template. */
    static PodSpec podSpec(ContainerSpec spec, KubernetesRuntimeConfig config) {
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

        var pod = new PodSpecBuilder()
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
            .withVolumes(volumes);
        if (config.runtimeClassName() != null && !config.runtimeClassName().isBlank()) {
            pod.withRuntimeClassName(config.runtimeClassName());
        }
        if (!config.nodeSelector().isEmpty()) {
            pod.withNodeSelector(config.nodeSelector());
        }
        return pod.build();
    }

    /** The per-task volume: ReadWriteOnce, sized and classed by the config. */
    static PersistentVolumeClaimSpec claimSpec(KubernetesRuntimeConfig config) {
        return new PersistentVolumeClaimSpecBuilder()
            .withAccessModes("ReadWriteOnce")
            .withStorageClassName(config.storageClass())
            .withNewResources()
            .withRequests(Map.of("storage", new Quantity(config.volumeSize())))
            .endResources()
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

    /** What a pod that is not running is doing, for an error message. */
    static String summary(Pod pod) {
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

    private static VolumeMount mount(String volume, String path, String subPath) {
        return new VolumeMountBuilder().withName(volume).withMountPath(path).withSubPath(subPath).build();
    }
}
