package dev.smithyai.orchestrator.service.docker;

import dev.smithyai.orchestrator.config.DockerConfig;
import dev.smithyai.orchestrator.config.OrchestratorConfig;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class ContainerRuntimeConfiguration {

    /**
     * Docker unless {@code runtime.kubernetes} is configured; the validator
     * rejects both. On Kubernetes, {@code runtime.kubernetes.backend} picks
     * StatefulSets (the default) or agent-sandbox Sandboxes.
     */
    @Bean
    public ContainerRuntime containerRuntime(
        OrchestratorConfig orchestratorConfig,
        DockerCli docker,
        DockerConfig dockerConfig
    ) {
        var runtime = orchestratorConfig.runtime();
        if (runtime != null && runtime.kubernetes() != null) {
            // In-cluster service account when running in a pod, the local
            // kubeconfig otherwise.
            var client = new KubernetesClientBuilder().build();
            if (runtime.kubernetes().usesSandbox()) {
                log.info("Task containers run on Kubernetes as agent-sandbox Sandboxes");
                return new SandboxRuntime(client, runtime.kubernetes());
            }
            log.info("Task containers run on Kubernetes as StatefulSets");
            return new KubernetesRuntime(client, runtime.kubernetes());
        }
        return new DockerRuntime(docker, dockerConfig.network());
    }
}
