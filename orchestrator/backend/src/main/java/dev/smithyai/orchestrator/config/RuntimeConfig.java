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
     * Task containers as pods in one namespace, each with its own volume.
     *
     * @param namespace        where task pods run; defaults to the orchestrator pod's own namespace
     * @param storageClass     for per-task and cache volumes; null uses the cluster default
     * @param volumeSize       per-task volume holding /workspace, /tmp and /root
     * @param cacheAccessMode  ReadWriteOnce is enough on one node; multi-node clusters need ReadWriteMany
     * @param resources        container resources, e.g. {@code requests: {cpu: 500m}}
     * @param startTimeout     seconds to wait for a task pod to be running
     * @param backend          what owns a task pod: {@code statefulset} (the default) or {@code sandbox},
     *                         an {@code agents.x-k8s.io/v1beta1} Sandbox from kubernetes-sigs/agent-sandbox
     * @param runtimeClassName RuntimeClass for task pods (gVisor, Kata); null uses the cluster default
     * @param nodeSelector     node labels task pods must land on
     * @param maxLifetime      sandbox backend only: seconds after which the controller deletes a task
     *                         the orchestrator forgot, as its {@code shutdownTime}; null or 0 never expires
     * @param failedPodPolicy  sandbox backend only: {@code Retain} or {@code Recreate} (needs
     *                         agent-sandbox v1.0.12 or later); null leaves the controller's default
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
        Integer startTimeout,
        String backend,
        String runtimeClassName,
        Map<String, String> nodeSelector,
        Integer maxLifetime,
        String failedPodPolicy
    ) {
        public static final String STATEFULSET = "statefulset";
        public static final String SANDBOX = "sandbox";
        public static final List<String> BACKENDS = List.of(STATEFULSET, SANDBOX);

        public KubernetesRuntimeConfig {
            caches = caches == null ? List.of() : List.copyOf(caches);
            imagePullSecrets = imagePullSecrets == null ? List.of() : List.copyOf(imagePullSecrets);
            resources = resources == null ? Map.of() : Map.copyOf(resources);
            nodeSelector = nodeSelector == null ? Map.of() : Map.copyOf(nodeSelector);
            if (volumeSize == null || volumeSize.isBlank()) volumeSize = "10Gi";
            if (cacheSize == null || cacheSize.isBlank()) cacheSize = "20Gi";
            if (cacheAccessMode == null || cacheAccessMode.isBlank()) cacheAccessMode = "ReadWriteOnce";
            if (startTimeout == null || startTimeout <= 0) startTimeout = 300;
            backend = backend == null || backend.isBlank() ? STATEFULSET : backend.strip().toLowerCase();
            if (maxLifetime != null && maxLifetime <= 0) maxLifetime = null;
            if (failedPodPolicy != null && failedPodPolicy.isBlank()) failedPodPolicy = null;
        }

        /** The shape before the sandbox backend: StatefulSets, no runtime class, no expiry. */
        public KubernetesRuntimeConfig(
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
            this(
                namespace,
                taskImage,
                storageClass,
                volumeSize,
                caches,
                cacheSize,
                cacheAccessMode,
                imagePullSecrets,
                resources,
                startTimeout,
                null,
                null,
                null,
                null,
                null
            );
        }

        public boolean usesSandbox() {
            return SANDBOX.equals(backend);
        }
    }
}
