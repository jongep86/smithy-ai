package dev.smithyai.orchestrator.testing;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.smithyai.orchestrator.config.BotConfig;
import dev.smithyai.orchestrator.config.ClaudeConfig;
import dev.smithyai.orchestrator.config.DockerConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.service.claude.ClaudeSession;
import dev.smithyai.orchestrator.service.claude.ClaudeTurnLostException;
import dev.smithyai.orchestrator.service.docker.ContainerRuntime;
import dev.smithyai.orchestrator.service.docker.ContainerService;
import dev.smithyai.orchestrator.service.docker.ContainerSession;
import dev.smithyai.orchestrator.service.docker.DockerCli;
import dev.smithyai.orchestrator.service.docker.DockerRuntime;
import dev.smithyai.orchestrator.service.docker.dto.ActiveTurn;
import dev.smithyai.orchestrator.service.docker.dto.ContainerState;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Detached turns against a real Docker daemon, with a stub in place of claude.
 *
 * <p>The orchestrator's death is simulated by a runtime that stops answering
 * the moment the turn has been launched: the first {@link ClaudeSession} fails
 * out of its wait, as a killed JVM would never return from it, and a second
 * one on a fresh {@link ContainerService} has to pick up the same turn.
 *
 * <p>Skipped unless Docker runs and the image is present
 * ({@code SMITHY_DETACHED_IT_IMAGE}, default {@code ubuntu:24.04}; any image
 * with util-linux and coreutils will do, every claude-task image qualifies).
 */
class DetachedTurnDockerIT {

    private static final String IMAGE = System.getenv().getOrDefault("SMITHY_DETACHED_IT_IMAGE", "ubuntu:24.04");
    private static final String CONTAINER = "smithy-it-detached-turn";

    /** Counts its own starts, reads the prompt, works for STUB_SECS, answers in JSON. */
    private static final String STUB = """
        #!/bin/sh
        echo start >> /tmp/stub-starts
        p=$(cat)
        sleep "$(cat /tmp/stub-secs 2>/dev/null || echo 4)"
        printf '{"result":"answer to %s"}' "$p"
        """;

    /** Kill the running turn without an exit code, once more than $0 turns have started. No procps needed. */
    private static final String KILL_TURN = """
        n=$(wc -l < /tmp/stub-starts 2>/dev/null || echo 0)
        [ "$n" -gt "$0" ] || exit 1
        p=$(cat /tmp/smithy-turns/*/pid 2>/dev/null | tail -1)
        [ -n "$p" ] || exit 1
        s=$(cut -d' ' -f6 /proc/"$p"/stat) || exit 1
        kill -KILL "$s" && kill -KILL -- "-$p"
        """;

    private ContainerService containers;

    @BeforeAll
    static void requireDocker() {
        assumeTrue(shell("docker info") == 0, "Docker is not running");
        assumeTrue(shell("docker image inspect " + IMAGE) == 0, IMAGE + " is not present (docker pull it)");
    }

    @BeforeEach
    void setUp() {
        shell("docker rm -f " + CONTAINER);
        assertEquals(
            0,
            shell("docker run -d --name " + CONTAINER + " --label smithy.managed=true " + IMAGE + " sleep infinity")
        );
        assertEquals(0, shell("docker exec " + CONTAINER + " mkdir -p /workspace"));
        containers = service(runtime());
        var c = session(containers);
        assertEquals(
            0,
            c
                .exec(
                    List.of("sh", "-c", "cat > /usr/bin/claude && chmod +x /usr/bin/claude"),
                    Duration.ofSeconds(30),
                    STUB
                )
                .exitCode()
        );
        c.writeState(ContainerState.init("it", "plan"));
        ClaudeSession.configureDetached(new ClaudeSession.DetachedSettings(true, Duration.ofSeconds(2), 1));
    }

    @AfterEach
    void tearDown() {
        ClaudeSession.configureDetached(ClaudeSession.DetachedSettings.OFF);
        shell("docker rm -f " + CONTAINER);
    }

    @Test
    void aTurnSurvivesTheOrchestratorAndIsCollectedByTheNextOne() {
        // First orchestrator: launches, then dies.
        var dead = new AtomicBoolean(false);
        var dying = new DyingRuntime(runtime(), dead);
        var first = new ClaudeSession(session(service(dying)), List.of("Read"));
        first.setModel("m");
        assertThrows(DyingRuntime.OrchestratorDied.class, () -> first.send("the question"));
        assertTrue(dead.get(), "died after the launch, not before");

        // The turn is running without anyone watching it.
        var c = session(containers);
        var turn = c.readState().activeTurn();
        assertEquals(ActiveTurn.Phase.LAUNCHED, turn.phase());
        assertEquals(0, c.exec("sh", "-c", "test -f /tmp/smithy-turns/" + turn.id() + "/pid").exitCode());

        // Second orchestrator: a new ClaudeSession, as after a restart, with a new session id.
        var second = new ClaudeSession(session(service(runtime())), List.of("Read"));
        second.setModel("m");
        assertEquals("answer to the question", second.send("the question"));

        assertEquals(first.getSessionId(), second.getSessionId(), "attached to the turn's session");
        assertEquals("start\n", c.exec("cat", "/tmp/stub-starts").stdout(), "exactly one claude process ran");
        assertEquals(ActiveTurn.Phase.COLLECTED, c.readState().activeTurn().phase());
    }

    @Test
    void aTurnKilledInsideTheContainerIsRelaunchedOnceThenReported() throws Exception {
        var c = session(containers);
        c.exec("sh", "-c", "echo 30 > /tmp/stub-secs");
        // Kill every turn as soon as it runs, the way an OOM kill or a lost pod ends it.
        var killer = new Thread(() -> {
            for (int i = 0; i < 2; i++) {
                for (int t = 0; t < 100; t++) {
                    // The launcher shell leads the turn's session (its pid is the sid);
                    // kill it first so it cannot write rc, then timeout's group.
                    var r = c.exec(List.of("sh", "-c", KILL_TURN, String.valueOf(i)));
                    if (r.exitCode() == 0) break;
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        });
        killer.start();

        var session = new ClaudeSession(c, List.of("Read"));
        session.setModel("m");
        var thrown = assertThrows(ClaudeTurnLostException.class, () -> session.send("doomed"));
        killer.join(TimeUnit.SECONDS.toMillis(30));

        assertEquals(2, thrown.getAttempts());
        assertEquals("start\nstart\n", c.exec("cat", "/tmp/stub-starts").stdout(), "one relaunch, not more");
    }

    @Test
    void cancelEndsTheTurnAndFreesTheSession() {
        var c = session(containers);
        c.exec("sh", "-c", "echo 60 > /tmp/stub-secs");
        var dead = new AtomicBoolean(false);
        var first = new ClaudeSession(session(service(new DyingRuntime(runtime(), dead))), List.of("Read"));
        first.setModel("m");
        assertThrows(DyingRuntime.OrchestratorDied.class, () -> first.send("long"));

        assertTrue(ClaudeSession.cancelActiveTurn(c));

        var turn = c.readState().activeTurn();
        assertEquals(ActiveTurn.Phase.COLLECTED, turn.phase());
        String rc = c.exec("cat", "/tmp/smithy-turns/" + turn.id() + "/rc").stdout().strip();
        assertEquals("143", rc, "SIGTERM reached the turn");
        assertEquals(
            0,
            c.exec("flock", "-n", "/tmp/smithy-turns/" + turn.id() + "/lock", "true").exitCode(),
            "nothing of the turn still runs"
        );
    }

    // ── Plumbing ─────────────────────────────────────────────

    private static DockerConfig dockerConfig() {
        return new DockerConfig("docker", "bridge", IMAGE, null);
    }

    private static ContainerRuntime runtime() {
        return new DockerRuntime(new DockerCli(dockerConfig()), "bridge");
    }

    private static ContainerService service(ContainerRuntime runtime) {
        return new ContainerService(
            dockerConfig(),
            new ClaudeConfig("it-token", null, "m"),
            vcsProviderConfig(),
            botConfig(),
            runtime
        );
    }

    private static ContainerSession session(ContainerService service) {
        return new ContainerSession(CONTAINER, service);
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
                "t",
                "t",
                "t"
            ),
            null,
            null,
            null
        );
    }

    private static int shell(String command) {
        try {
            var p = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(120, TimeUnit.SECONDS) ? p.exitValue() : -1;
        } catch (IOException | InterruptedException e) {
            return -1;
        }
    }
}
