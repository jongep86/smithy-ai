package dev.smithyai.orchestrator.service.docker;

import dev.smithyai.orchestrator.config.DockerConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ContainerRuntimeConfiguration {

    @Bean
    public ContainerRuntime containerRuntime(DockerCli docker, DockerConfig dockerConfig) {
        return new DockerRuntime(docker, dockerConfig.network());
    }
}
