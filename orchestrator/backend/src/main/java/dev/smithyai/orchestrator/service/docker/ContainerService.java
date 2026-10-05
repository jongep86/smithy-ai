package dev.smithyai.orchestrator.service.docker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.smithyai.orchestrator.config.BotConfig;
import dev.smithyai.orchestrator.config.ClaudeConfig;
import dev.smithyai.orchestrator.config.ConnectorRegistry;
import dev.smithyai.orchestrator.config.DockerConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.service.docker.dto.ContainerConfig;
import dev.smithyai.orchestrator.service.docker.dto.ContainerSpec;
import dev.smithyai.orchestrator.service.docker.dto.ContainerState;
import dev.smithyai.orchestrator.service.docker.dto.ExecResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ContainerService {

    private static final String STATE_PATH = "/tmp/smithy-state.json";
    static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        // State files may be written by a newer orchestrator than the one
        // reading them (rollback, adopted container); an unknown field is not
        // a reason to strand the run.
        .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final int INIT_TIMEOUT_SECONDS = 300;
    private static final int INIT_POLL_INTERVAL_MS = 1000;

    private final ContainerRuntime runtime;
    private final String taskImage;
    private final String vcsUrl;
    private final String vcsToken;
    private final String claudeOauthToken;
    private final String claudeApiKey;
    private final String gitAuthUser;
    private final String defaultGitEmail;

    @Autowired
    public ContainerService(
        DockerConfig dockerConfig,
        ClaudeConfig claudeConfig,
        ConnectorRegistry connectors,
        ContainerRuntime runtime
    ) {
        String connector = connectors.defaultVcs();
        String actor = connectors.defaultActor();
        this.runtime = runtime;
        this.taskImage = dockerConfig.taskImage();
        this.vcsUrl = connectors.connector(connector).url();
        this.vcsToken = connectors.token(connector, actor);
        this.claudeOauthToken = claudeConfig.oauthToken();
        this.claudeApiKey = claudeConfig.apiKey();
        this.gitAuthUser = connectors.gitAuthUser(connector);
        this.defaultGitEmail = connectors.gitEmail(connector, actor);
    }

    public ContainerService(
        DockerConfig dockerConfig,
        ClaudeConfig claudeConfig,
        VcsProviderConfig vcsConfig,
        BotConfig botConfig,
        DockerCli docker
    ) {
        this(dockerConfig, claudeConfig, vcsConfig, botConfig, new DockerRuntime(docker, dockerConfig.network()));
    }

    public ContainerService(
        DockerConfig dockerConfig,
        ClaudeConfig claudeConfig,
        VcsProviderConfig vcsConfig,
        BotConfig botConfig,
        ContainerRuntime runtime
    ) {
        this.runtime = runtime;
        this.taskImage = dockerConfig.taskImage();
        this.vcsUrl = vcsConfig.resolvedUrl();
        this.vcsToken = vcsConfig.smithyToken();
        this.claudeOauthToken = claudeConfig.oauthToken();
        this.claudeApiKey = claudeConfig.apiKey();
        this.gitAuthUser = vcsConfig.gitAuthUser();
        this.defaultGitEmail = botConfig.resolvedSmithyEmail();
    }

    // ── Public API ───────────────────────────────────────────

    public ContainerSession createSession(String name) {
        return new ContainerSession(name, this);
    }

    /**
     * Create a session pre-seeded with state already read during recovery, so
     * callers of getState() don't need the container to be running.
     */
    public ContainerSession createSession(String name, ContainerState seedState) {
        return new ContainerSession(name, this, seedState);
    }

    public boolean containerExists(String containerName) {
        return runtime.exists(containerName);
    }

    public List<String> listManagedContainers() {
        return listManaged(false);
    }

    public List<String> listAllManagedContainers() {
        return listManaged(true);
    }

    private List<String> listManaged(boolean includeStopped) {
        try {
            return runtime.listManaged(includeStopped);
        } catch (RuntimeException e) {
            log.warn("Failed to list managed containers: {}", e.getMessage());
            return List.of();
        }
    }

    public boolean ensureRunning(String containerName) {
        var running = runtime.isRunning(containerName);
        if (running.isEmpty()) {
            return false;
        }
        if (running.get()) {
            return true;
        }
        log.info("Container {} is stopped, starting it", containerName);
        return runtime.start(containerName);
    }

    public boolean isManagedContainer(String containerName) {
        return listAllManagedContainers().contains(containerName);
    }

    public Optional<ContainerState> readStateSafe(String containerName) {
        try {
            byte[] data = copyFromContainer(containerName, STATE_PATH);
            return Optional.of(MAPPER.readValue(data, ContainerState.class));
        } catch (Exception e) {
            log.warn("Failed to read state from {}: {}", containerName, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Container lifecycle ──────────────────────────────────

    void create(String name, ContainerConfig init) {
        var labels = new LinkedHashMap<String, String>();
        labels.put("smithy.managed", "true");
        if (init.workflow() != null) {
            labels.put("smithy.workflow", init.workflow());
        }

        var env = new LinkedHashMap<String, String>();
        if (claudeOauthToken != null && !claudeOauthToken.isBlank()) {
            env.put("CLAUDE_CODE_OAUTH_TOKEN", claudeOauthToken);
        }
        if (claudeApiKey != null && !claudeApiKey.isBlank()) {
            env.put("ANTHROPIC_API_KEY", claudeApiKey);
        }
        env.put("VCS_URL", init.vcsUrl() != null ? init.vcsUrl() : vcsUrl);
        env.put("VCS_TOKEN", init.vcsToken() != null ? init.vcsToken() : vcsToken);
        env.put("CLONE_URL", init.cloneUrl());
        env.put("BRANCH", init.branch());
        env.put("SOURCE_BRANCH", init.sourceBranch() != null ? init.sourceBranch() : "");
        env.put("GIT_EMAIL", init.gitEmail() != null ? init.gitEmail() : defaultGitEmail);
        env.put("GIT_USERNAME", init.gitUsername() != null ? init.gitUsername() : "Agent Smithy");

        String extraReposJson = "";
        if (init.extraRepos() != null && !init.extraRepos().isEmpty()) {
            try {
                var repoLists = init
                    .extraRepos()
                    .stream()
                    .map(r -> List.of(r.cloneUrl(), r.path(), r.branch()))
                    .toList();
                extraReposJson = MAPPER.writeValueAsString(repoLists);
            } catch (Exception e) {
                // Continuing would build a container missing a repository the
                // workflow asked for, and the agent would then plan against
                // code it cannot see.
                throw new IllegalStateException("Cannot serialize extra repos for container " + name, e);
            }
        }
        env.put("GIT_AUTH_USER", init.gitAuthUser() != null ? init.gitAuthUser() : gitAuthUser);
        env.put("EXTRA_REPOS", extraReposJson);

        runtime.create(new ContainerSpec(name, taskImage, List.of("smithy-init"), labels, init.cacheVolumes(), env));

        log.info("Created container {}, waiting for init...", name);
        waitForInit(name);
    }

    private void waitForInit(String name) {
        long deadline = System.currentTimeMillis() + INIT_TIMEOUT_SECONDS * 1000L;
        while (System.currentTimeMillis() < deadline) {
            // Check for success marker
            var doneCheck = runtime.exec(name, List.of("test", "-f", "/tmp/smithy-init-done"), null, null, null, null);
            if (doneCheck.exitCode() == 0) {
                log.info("Container {} init completed successfully", name);
                return;
            }

            // Check for failure marker
            var failCheck = runtime.exec(
                name,
                List.of("test", "-f", "/tmp/smithy-init-failed"),
                null,
                null,
                null,
                null
            );
            if (failCheck.exitCode() == 0) {
                String logs = fetchLogs(name, 50);
                throw new RuntimeException("Container " + name + " init failed. Logs:\n" + logs);
            }

            // Check if container is still running
            if (!runtime.isRunning(name).orElse(false)) {
                String logs = fetchLogs(name, 50);
                throw new RuntimeException("Container " + name + " stopped during init. Logs:\n" + logs);
            }

            try {
                Thread.sleep(INIT_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for container " + name + " init", e);
            }
        }
        log.warn("Container {} init did not complete within {}s — proceeding anyway", name, INIT_TIMEOUT_SECONDS);
    }

    public String fetchLogs(String name, int tailLines) {
        return runtime.logs(name, tailLines);
    }

    public String fetchOwnLogs(int tailLines) {
        return runtime.ownLogs(tailLines);
    }

    /**
     * Reads the Claude Code session transcript (JSONL) for a given session id.
     * Requires the container to be running, since it shells in to read the file.
     */
    public String fetchSessionTranscript(String containerName, String sessionId) {
        var result = runtime.exec(
            containerName,
            List.of(
                "sh",
                "-c",
                "cat \"$(find /root/.claude/projects -name '" +
                    sessionId +
                    ".jsonl' 2>/dev/null | head -1)\" 2>/dev/null"
            ),
            null,
            null,
            null,
            null
        );
        return result.stdout();
    }

    void destroy(String containerName) {
        runtime.remove(containerName);
        log.info("Destroyed container {}", containerName);
    }

    // ── Exec ─────────────────────────────────────────────────

    ExecResult exec(
        String containerName,
        List<String> command,
        Map<String, String> environment,
        Duration timeout,
        String stdinInput
    ) {
        byte[] stdin = stdinInput != null ? stdinInput.getBytes(StandardCharsets.UTF_8) : null;
        return runtime.exec(containerName, command, environment, "/workspace", stdin, timeout);
    }

    // ── State ────────────────────────────────────────────────

    ContainerState readState(String containerName) {
        try {
            byte[] data = copyFromContainer(containerName, STATE_PATH);
            return MAPPER.readValue(data, ContainerState.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read state from: " + containerName, e);
        }
    }

    void writeState(String containerName, ContainerState state) {
        try {
            byte[] data = MAPPER.writeValueAsBytes(state);
            copyToContainer(containerName, "/tmp", data, "smithy-state.json");
        } catch (Exception e) {
            throw new RuntimeException("Failed to write state to: " + containerName, e);
        }
    }

    // ── File transfer ────────────────────────────────────────

    byte[] copyFromContainer(String containerName, String path) {
        return runtime.execForBytes(containerName, List.of("cat", path), Duration.ofSeconds(30));
    }

    void copyToContainer(String containerName, String destDir, byte[] data, String filename) {
        // Shell-quote the path to prevent injection
        String safePath = destDir + "/" + filename;
        var result = runtime.exec(
            containerName,
            List.of("sh", "-c", "cat > '" + safePath.replace("'", "'\\''") + "'"),
            null,
            null,
            data,
            Duration.ofSeconds(30)
        );
        if (result.exitCode() != 0) {
            throw new RuntimeException("Failed to copy to " + containerName + ":" + safePath + ": " + result.stderr());
        }
    }
}
