package dev.smithyai.orchestrator.service.docker;

import static org.junit.jupiter.api.Assertions.*;

import dev.smithyai.orchestrator.config.BotConfig;
import dev.smithyai.orchestrator.config.ClaudeConfig;
import dev.smithyai.orchestrator.config.DockerConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.service.docker.dto.ContainerConfig;
import dev.smithyai.orchestrator.testing.FakeDockerCli;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the exact docker command lines, so moving them behind
 * {@link ContainerRuntime} cannot change what a Docker deployment runs.
 */
class DockerArgumentsTest {

    private FakeDockerCli docker;
    private ContainerService containers;

    @BeforeEach
    void setUp() {
        docker = new FakeDockerCli();
        containers = new ContainerService(
            new DockerConfig("docker", "smithy-net", "claude-task:test", "npm,go"),
            new ClaudeConfig("test-token", null, "claude-opus-5"),
            vcsProviderConfig(),
            botConfig(),
            docker
        );
    }

    @Test
    void createRunsTheSameDockerCreateAndStart() {
        var caches = new LinkedHashMap<String, String>();
        caches.put("cache-npm", "/root/.npm");
        var config = ContainerConfig.builder()
            .cloneUrl("http://forgejo.invalid/o/r.git")
            .branch("smithy/1")
            .cacheVolumes(caches)
            .workflow("smithy")
            .build();

        containers.create("smithy-o-r-1", config);

        assertEquals(
            List.of(
                "create",
                "--name",
                "smithy-o-r-1",
                "--network",
                "smithy-net",
                "--restart",
                "unless-stopped",
                "--label",
                "smithy.managed=true",
                "--label",
                "smithy.workflow=smithy",
                "-v",
                "cache-npm:/root/.npm",
                "-e",
                "CLAUDE_CODE_OAUTH_TOKEN=test-token",
                "-e",
                "VCS_URL=http://forgejo.invalid",
                "-e",
                "VCS_TOKEN=smithy-token",
                "-e",
                "CLONE_URL=http://forgejo.invalid/o/r.git",
                "-e",
                "BRANCH=smithy/1",
                "-e",
                "SOURCE_BRANCH=",
                "-e",
                "GIT_EMAIL=smithy@localhost",
                "-e",
                "GIT_USERNAME=Agent Smithy",
                "-e",
                "GIT_AUTH_USER=token",
                "-e",
                "EXTRA_REPOS=",
                "claude-task:test",
                "smithy-init"
            ),
            docker.invocations.get(0)
        );
        assertEquals(List.of("start", "smithy-o-r-1"), docker.invocations.get(1));
        assertEquals(List.of("exec", "smithy-o-r-1", "test", "-f", "/tmp/smithy-init-done"), docker.invocations.get(2));
    }

    @Test
    void execRunsInWorkspaceWithEnvironmentAndStdin() {
        var env = new LinkedHashMap<String, String>();
        env.put("A", "1");
        env.put("B", "2");

        containers.exec("c", List.of("git", "status"), env, Duration.ofSeconds(5), "in");
        containers.exec("c", List.of("ls"), null, null, null);

        assertEquals(
            List.of("exec", "-i", "-w", "/workspace", "-e", "A=1", "-e", "B=2", "c", "git", "status"),
            docker.invocations.get(0)
        );
        assertEquals(List.of("exec", "-w", "/workspace", "c", "ls"), docker.invocations.get(1));
    }

    @Test
    void fileTransferAndRemovalUseTheSameCommands() {
        docker.withFile("c", "/tmp/x", "hello");

        byte[] read = containers.copyFromContainer("c", "/tmp/x");
        containers.copyToContainer("c", "/tmp", "data".getBytes(StandardCharsets.UTF_8), "it's.json");
        containers.destroy("c");

        assertEquals("hello", new String(read, StandardCharsets.UTF_8));
        assertEquals(List.of("exec", "c", "cat", "/tmp/x"), docker.invocations.get(0));
        assertEquals(List.of("exec", "-i", "c", "sh", "-c", "cat > '/tmp/it'\\''s.json'"), docker.invocations.get(1));
        assertEquals(List.of("stop", "c"), docker.invocations.get(2));
        assertEquals(List.of("rm", "-f", "c"), docker.invocations.get(3));
    }

    @Test
    void inventoryAndRestartUseTheSameCommands() {
        containers.listManagedContainers();
        containers.listAllManagedContainers();
        containers.ensureRunning("missing");

        assertEquals(
            List.of("ps", "--filter", "label=smithy.managed=true", "--format", "{{.Names}}"),
            docker.invocations.get(0)
        );
        assertEquals(
            List.of("ps", "-a", "--filter", "label=smithy.managed=true", "--format", "{{.Names}}"),
            docker.invocations.get(1)
        );
        assertEquals(List.of("inspect", "--format", "{{.State.Running}}", "missing"), docker.invocations.get(2));
    }

    @Test
    void goCacheMountsTheModuleCache() {
        Map<String, String> volumes = new DockerConfig("docker", "n", "i", "npm, go").getCacheVolumeMap();

        assertEquals(Map.of("cache-npm", "/root/.npm", "cache-go", "/root/go/pkg/mod"), volumes);
    }

    private static BotConfig botConfig() {
        return new BotConfig(
            new BotConfig.BotEntry("smithy", "smithy@localhost"),
            new BotConfig.BotEntry("architect", "architect@localhost"),
            new BotConfig.BotEntry("coordinator", "coordinator@localhost")
        );
    }

    private static VcsProviderConfig vcsProviderConfig() {
        return new VcsProviderConfig(
            "forgejo",
            null,
            new VcsProviderConfig.ForgejoProviderConfig(
                "http://forgejo.invalid",
                "http://forgejo.invalid",
                null,
                "smithy-token",
                "architect-token",
                "coordinator-token"
            ),
            null,
            null,
            null
        );
    }
}
