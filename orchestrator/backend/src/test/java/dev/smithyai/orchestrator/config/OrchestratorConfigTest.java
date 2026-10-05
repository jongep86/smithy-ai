package dev.smithyai.orchestrator.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class OrchestratorConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsNamedConnectorsActorsAndSecretsFromEnvironmentAndFiles() throws Exception {
        Path webhookSecret = tempDir.resolve("webhook-secret");
        Files.writeString(webhookSecret, "hook-from-file\n");
        Path configFile = tempDir.resolve("orchestrator.yml");
        Files.writeString(configFile, config(webhookSecret));
        var environment = new MockEnvironment()
            .withProperty("ORCHESTRATOR_CONFIG", configFile.toString())
            .withProperty("CLAUDE_TOKEN", "claude-from-env")
            .withProperty("FORGEJO_SMITHY_TOKEN", "forgejo-from-env");

        var loader = new ConfigLoader(environment);
        var config = loader.orchestratorConfig();
        var registry = new ConnectorRegistry(config, environment);

        assertEquals("forgejo-main", config.defaults().vcs());
        assertEquals("hook-from-file", registry.webhookSecret("forgejo-main"));
        assertEquals("forgejo-from-env", registry.token("forgejo-main", "smithy"));
        assertEquals("smithy-bot", registry.assignee("forgejo-main", "smithy"));
        assertEquals("jira-account", registry.assignee("jira-product", "smithy"));
        assertEquals("forgejo-main", registry.defaultIssueTracker("forgejo-main"));
        assertEquals("jira-product", registry.defaultIssueTracker("jira-product"));
        assertEquals("build/test.db", loader.storageConfig().resolvedDatabase());
        assertEquals("forgejo-main", config.repositoryCatalogs().get("product").getFirst().source());
        var missingActor = assertThrows(IllegalArgumentException.class, () ->
            registry.username("forgejo-main", "architect")
        );
        assertTrue(missingActor.getMessage().contains("no identity for actor 'architect'"));
    }

    @Test
    void rejectsUnknownConfigurationFields() throws Exception {
        Path configFile = tempDir.resolve("orchestrator.yml");
        Files.writeString(configFile, config(tempDir.resolve("secret")) + "\nunknownSetting: true\n");
        Files.writeString(tempDir.resolve("secret"), "hook");
        var environment = new MockEnvironment()
            .withProperty("ORCHESTRATOR_CONFIG", configFile.toString())
            .withProperty("CLAUDE_TOKEN", "claude")
            .withProperty("FORGEJO_SMITHY_TOKEN", "forgejo");

        var error = assertThrows(java.io.UncheckedIOException.class, () -> new ConfigLoader(environment));
        assertTrue(error.getMessage().contains("parse orchestrator config"));
    }

    @Test
    void secretReferencesNeverRenderTheirLiteralValue() {
        assertEquals("SecretRef(redacted)", SecretRef.literal("do-not-print-me").toString());
    }

    @Test
    void missingDefaultVcsHasAClearValidationError() throws Exception {
        Path webhookSecret = tempDir.resolve("webhook-secret");
        Files.writeString(webhookSecret, "hook");
        Path configFile = tempDir.resolve("orchestrator.yml");
        Files.writeString(configFile, config(webhookSecret).replace("vcs: forgejo-main", "vcs:"));
        var environment = new MockEnvironment()
            .withProperty("ORCHESTRATOR_CONFIG", configFile.toString())
            .withProperty("CLAUDE_TOKEN", "claude")
            .withProperty("FORGEJO_SMITHY_TOKEN", "forgejo");

        var error = assertThrows(IllegalStateException.class, () -> new ConfigLoader(environment));

        assertEquals("defaults.vcs is required in orchestrator.yml", error.getMessage());
    }

    @Test
    void kubernetesRuntimeSuppliesTaskImageAndCaches() throws Exception {
        var loader = new ConfigLoader(environmentFor(config(secret()).replace(DOCKER_BLOCK, KUBERNETES_BLOCK)));

        var kubernetes = loader.orchestratorConfig().runtime().kubernetes();
        assertNull(loader.orchestratorConfig().runtime().docker());
        assertEquals("smithy-tasks", kubernetes.namespace());
        assertEquals("10Gi", kubernetes.volumeSize());
        assertEquals("ReadWriteOnce", kubernetes.cacheAccessMode());
        assertEquals(300, kubernetes.startTimeout());
        assertEquals(java.util.List.of("ghcr-creds"), kubernetes.imagePullSecrets());
        assertEquals("1Gi", kubernetes.resources().get("requests").get("memory"));
        assertEquals("task:k8s", loader.dockerConfig().taskImage());
        assertEquals(java.util.Map.of("cache-go", "/root/go/pkg/mod"), loader.dockerConfig().getCacheVolumeMap());
    }

    @Test
    void dockerAndKubernetesRuntimesCannotBothBeSet() throws Exception {
        String both = config(secret()).replace(DOCKER_BLOCK, DOCKER_BLOCK + KUBERNETES_BLOCK.replace("runtime:\n", ""));

        var error = assertThrows(IllegalStateException.class, () -> new ConfigLoader(environmentFor(both)));

        assertEquals(
            "runtime.docker and runtime.kubernetes cannot both be set in orchestrator.yml",
            error.getMessage()
        );
    }

    @Test
    void dockerRemainsTheRuntimeWithoutAKubernetesBlock() throws Exception {
        var loader = new ConfigLoader(environmentFor(config(secret())));

        assertNull(loader.orchestratorConfig().runtime().kubernetes());
        assertEquals("task:test", loader.dockerConfig().taskImage());
    }

    @Test
    void runtimeBeanFollowsTheConfiguredBlock() throws Exception {
        var beans = new dev.smithyai.orchestrator.service.docker.ContainerRuntimeConfiguration();
        var docker = new ConfigLoader(environmentFor(config(secret())));
        var kubernetes = new ConfigLoader(environmentFor(config(secret()).replace(DOCKER_BLOCK, KUBERNETES_BLOCK)));
        var cli = new dev.smithyai.orchestrator.testing.FakeDockerCli();

        assertInstanceOf(
            dev.smithyai.orchestrator.service.docker.DockerRuntime.class,
            beans.containerRuntime(docker.orchestratorConfig(), cli, docker.dockerConfig())
        );
        assertInstanceOf(
            dev.smithyai.orchestrator.service.docker.KubernetesRuntime.class,
            beans.containerRuntime(kubernetes.orchestratorConfig(), cli, kubernetes.dockerConfig())
        );
    }

    private static final String DOCKER_BLOCK = """
        runtime:
          docker:
            command: docker
            network: test
            taskImage: task:test
            caches: [gradle]
        """;

    private static final String KUBERNETES_BLOCK = """
        runtime:
          kubernetes:
            namespace: smithy-tasks
            taskImage: task:k8s
            caches: [go]
            imagePullSecrets: [ghcr-creds]
            resources:
              requests: {memory: 1Gi}
        """;

    private Path secret() throws Exception {
        Path webhookSecret = tempDir.resolve("webhook-secret");
        Files.writeString(webhookSecret, "hook");
        return webhookSecret;
    }

    private MockEnvironment environmentFor(String yaml) throws Exception {
        Path configFile = tempDir.resolve("orchestrator.yml");
        Files.writeString(configFile, yaml);
        return new MockEnvironment()
            .withProperty("ORCHESTRATOR_CONFIG", configFile.toString())
            .withProperty("CLAUDE_TOKEN", "claude")
            .withProperty("FORGEJO_SMITHY_TOKEN", "forgejo");
    }

    private static String config(Path webhookSecret) {
        return """
        apiVersion: smithy.ai/v1alpha1
        kind: OrchestratorConfig
        storage:
          database: build/test.db
          metrics: build/metrics.jsonl
        runtime:
          docker:
            command: docker
            network: test
            taskImage: task:test
            caches: [gradle]
        agent:
          claude:
            model: test-model
            oauthToken: {env: CLAUDE_TOKEN}
        auth:
          admin:
            passwordHash: {literal: ""}
        connectors:
          forgejo-main:
            provider: forgejo
            url: http://forgejo.internal
            externalUrl: https://forgejo.example
            webhookSecret: {file: "%s"}
            actors:
              smithy:
                username: smithy-bot
                token: {env: FORGEJO_SMITHY_TOKEN}
                git: {name: Smithy, email: smithy@example.com}
          jira-product:
            provider: jira
            url: https://jira.example
            webhookSecret: {literal: jira-hook}
            actors:
              smithy:
                accountId: jira-account
                email: smithy@example.com
                apiToken: {literal: jira-token}
            issueMapping:
              repositoryField: customfield_123
              allowStoriesWithoutRepository: true
        defaults:
          vcs: forgejo-main
          issueTracker: event.source
          actor: smithy
        workflows:
          definitionsDir: /config/workflows
          repositoryWorkflows: true
          defaults:
            branchPrefix: smithy/
            planApprovedLabel: Plan Approved
        repositoryCatalogs:
          product:
            - {source: forgejo-main, owner: acme, repo: api, description: HTTP API}
        knowledgebase: {enabled: false}
        ci: {autofix: false}
        """.formatted(webhookSecret.toString());
    }
}
