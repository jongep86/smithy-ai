package dev.smithyai.orchestrator.service.docker;

import dev.smithyai.orchestrator.config.RuntimeConfig.KubernetesRuntimeConfig;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/** What both Kubernetes runtimes do to the task namespace besides owning pods. */
@Slf4j
final class TaskNamespace {

    private TaskNamespace() {}

    /** The configured namespace, else the one the client runs in; never blank. */
    static String resolve(KubernetesClient client, KubernetesRuntimeConfig config) {
        String namespace =
            config.namespace() != null && !config.namespace().isBlank() ? config.namespace() : client.getNamespace();
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalStateException("runtime.kubernetes.namespace is required outside a cluster");
        }
        return namespace;
    }

    static void ensureCacheClaim(
        KubernetesClient client,
        String namespace,
        String cache,
        KubernetesRuntimeConfig config
    ) {
        var claims = client.persistentVolumeClaims().inNamespace(namespace);
        if (claims.withName(TaskPods.cacheClaimName(cache)).get() == null) {
            claims.resource(TaskPods.cacheClaimFor(cache, config)).create();
            log.info("Created cache claim {}", TaskPods.cacheClaimName(cache));
        }
    }

    /** Ids (the {@code smithy.ai/container-id} label) of managed pods that are running. */
    static List<String> runningIds(KubernetesClient client, String namespace) {
        return client
            .pods()
            .inNamespace(namespace)
            .withLabel(TaskPods.MANAGED_LABEL, "true")
            .list()
            .getItems()
            .stream()
            .filter(TaskPods::isRunning)
            .map(p -> p.getMetadata().getLabels().get(TaskPods.ID_LABEL))
            .toList();
    }

    static boolean awaitRunning(KubernetesClient client, String namespace, String pod, int timeoutSeconds) {
        try {
            client
                .pods()
                .inNamespace(namespace)
                .withName(pod)
                .waitUntilCondition(TaskPods::isRunning, timeoutSeconds, TimeUnit.SECONDS);
            return true;
        } catch (KubernetesClientException e) {
            return false;
        }
    }

    /** The orchestrator's name for the container, kept in an annotation; the object name otherwise. */
    static String originalName(ObjectMeta metadata) {
        var annotations = metadata.getAnnotations();
        String original = annotations == null ? null : annotations.get(TaskPods.NAME_ANNOTATION);
        return original != null ? original : metadata.getName();
    }
}
