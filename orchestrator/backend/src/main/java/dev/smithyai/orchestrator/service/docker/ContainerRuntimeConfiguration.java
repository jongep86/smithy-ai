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

    /** Docker unless {@code runtime.kubernetes} is configured; the validator rejects both. */
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
            var kubernetes = new KubernetesRuntime(new KubernetesClientBuilder().build(), runtime.kubernetes());
            log.info("Task containers run on Kubernetes");
            return kubernetes;
        }
        return new DockerRuntime(docker, dockerConfig.network());
    }
}
