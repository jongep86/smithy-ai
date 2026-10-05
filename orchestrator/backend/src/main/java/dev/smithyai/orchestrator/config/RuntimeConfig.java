package dev.smithyai.orchestrator.config;

import java.util.List;
import java.util.Map;

/** Where task containers run: {@code docker} (the default) or {@code kubernetes}, never both. */
public record RuntimeConfig(DockerRuntimeConfig docker, KubernetesRuntimeConfig kubernetes) {
    public record DockerRuntimeConfig(String command, String network, String taskImage, List<String> caches) {
        public DockerRuntimeConfig {
            caches = caches == null ? List.of() : List.copyOf(caches);
        }
    }

    /**
     * Task containers as single-replica StatefulSets in one namespace.
     *
     * @param namespace       where task pods run; defaults to the orchestrator pod's own namespace
     * @param storageClass    for per-task and cache volumes; null uses the cluster default
     * @param volumeSize      per-task volume holding /workspace, /tmp and /root
     * @param cacheAccessMode ReadWriteOnce is enough on one node; multi-node clusters need ReadWriteMany
     * @param resources       container resources, e.g. {@code requests: {cpu: 500m}}
     * @param startTimeout    seconds to wait for a task pod to be running
     */
    public record KubernetesRuntimeConfig(
        String namespace,
        String taskImage,
        String storageClass,
        String volumeSize,
        List<String> caches,
        String cacheSize,
        String cacheAccessMode,
        List<String> imagePullSecrets,
        Map<String, Map<String, String>> resources,
        Integer startTimeout
    ) {
        public KubernetesRuntimeConfig {
            caches = caches == null ? List.of() : List.copyOf(caches);
            imagePullSecrets = imagePullSecrets == null ? List.of() : List.copyOf(imagePullSecrets);
            resources = resources == null ? Map.of() : Map.copyOf(resources);
            if (volumeSize == null || volumeSize.isBlank()) volumeSize = "10Gi";
            if (cacheSize == null || cacheSize.isBlank()) cacheSize = "20Gi";
            if (cacheAccessMode == null || cacheAccessMode.isBlank()) cacheAccessMode = "ReadWriteOnce";
            if (startTimeout == null || startTimeout <= 0) startTimeout = 300;
        }
    }
}
