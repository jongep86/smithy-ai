package dev.smithyai.orchestrator.service.claude;

import static org.junit.jupiter.api.Assertions.*;

import dev.smithyai.orchestrator.config.AgentConfig.ClaudeAgentConfig;
import dev.smithyai.orchestrator.config.BotConfig;
import dev.smithyai.orchestrator.config.ClaudeConfig;
import dev.smithyai.orchestrator.config.DockerConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.service.claude.FakeTurnRuntime.Play;
import dev.smithyai.orchestrator.service.docker.ContainerService;
import dev.smithyai.orchestrator.service.docker.ContainerSession;
import dev.smithyai.orchestrator.service.docker.dto.ActiveTurn;
import dev.smithyai.orchestrator.service.docker.dto.ContainerState;
import dev.smithyai.orchestrator.service.docker.dto.ExecResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Detached turns: start, observe, attach after a restart, lose and relaunch,
 * cancel. One test per row of the attach table in the design.
 */
class ClaudeSessionDetachedTest {

    private static final String MODEL = "m";
    private static final String PROMPT = "Plan the feature";

    private FakeTurnRuntime runtime;
    private ContainerService containers;
    private MutableClock clock;
    private final List<Duration> sleeps = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ClaudeSession.configureDetached(new ClaudeSession.DetachedSettings(true, Duration.ofSeconds(20), 1));
        ClaudeSession.configureTurnTimeout(Duration.ofMinutes(60));
        runtime = new FakeTurnRuntime();
        containers = new ContainerService(dockerConfig(), claudeConfig(), vcsProviderConfig(), botConfig(), runtime);
        clock = new MutableClock(Instant.parse("2026-11-23T00:00:00Z"));
        container().writeState(ContainerState.init("wf", "plan"));
    }

    @AfterEach
    void restoreDefaults() {
        ClaudeSession.configureDetached(ClaudeSession.DetachedSettings.OFF);
        ClaudeSession.configureTurnTimeout(Duration.ofMinutes(60));
    }

    // ── Starting and collecting ──────────────────────────────

    @Test
    void aTurnIsLaunchedObservedAndCollected() {
        runtime.plays.add(Play.result("the plan").runningFor(3));

        String result = session().send(PROMPT);

        assertEquals("the plan", result);
        assertEquals(1, runtime.launches);
        var turn = runtime.launched().getFirst();
        assertEquals(PROMPT, turn.prompt, "the prompt travels as a file, not on the stream");
        assertEquals(
            List.of("timeout", "--kill-after=10s", "3600s", "/usr/bin/claude", "-p", "-"),
            turn.argv.subList(0, 6)
        );
        assertEquals(4, runtime.count(DetachedTurn.STATUS), "three RUNNING, one DONE");
        assertTrue(
            runtime.execs
                .stream()
                .noneMatch(c -> c.contains("/usr/bin/claude") && !c.get(2).equals(DetachedTurn.LAUNCHER)),
            "no exec carries the turn itself"
        );

        var recorded = state().activeTurn();
        assertEquals(ActiveTurn.Phase.COLLECTED, recorded.phase());
        assertEquals(DetachedTurn.dir(recorded.id()), turn.dir);
        assertEquals(ClaudeSession.promptHash(PROMPT, null, "default", MODEL), recorded.promptHash());
        assertEquals(clock.instant().plus(Duration.ofMinutes(60)), recorded.deadline());
    }

    @Test
    void theTurnIsRecordedBeforeItIsLaunched() {
        runtime.plays.add(Play.result("x"));
        session().send(PROMPT);

        int stateWrite = -1;
        int launch = -1;
        for (int i = 0; i < runtime.execs.size(); i++) {
            var c = runtime.execs.get(i);
            if (c.size() > 2 && c.get(2).startsWith("cat > '/tmp/smithy-state.json'") && stateWrite < 0) stateWrite = i;
            if (c.size() > 2 && c.get(2).equals(DetachedTurn.LAUNCHER)) launch = i;
        }
        assertTrue(
            stateWrite >= 0 && stateWrite < launch,
            "state written at " + stateWrite + ", launched at " + launch
        );
    }

    @Test
    void structuredOutputComesFromTheResultFile() {
        runtime.plays.add(Play.done("{\"result\":\"\",\"structured_output\":{\"answer\":42}}"));

        var result = session().sendStructured(PROMPT, "{\"type\":\"object\"}");

        assertEquals(Map.of("answer", 42), result);
        assertTrue(runtime.launched().getFirst().argv.contains("--json-schema"));
    }

    @Test
    void exitCode124IsATimeoutWithTheTurnsBudget() {
        runtime.plays.add(Play.exit(124, ""));

        var thrown = assertThrows(ClaudeTimeoutException.class, () -> session().send(PROMPT));

        assertEquals(Duration.ofMinutes(60), thrown.getBudget());
        assertEquals(ActiveTurn.Phase.COLLECTED, state().activeTurn().phase());
    }

    @Test
    void aNonzeroExitCarriesStderr() {
        runtime.plays.add(Play.exit(1, "Invalid API key"));

        var thrown = assertThrows(IllegalStateException.class, () -> session().send(PROMPT));

        assertFalse(thrown instanceof ClaudeTimeoutException);
        assertTrue(thrown.getMessage().contains("Invalid API key"), thrown.getMessage());
    }

    @Test
    void aResultOverTheCapIsRefusedNamingTheCapAndTheDirectory() {
        runtime.plays.add(Play.result("x".repeat(100)));
        var session = session();
        session.useOutputCap(50);

        var thrown = assertThrows(IllegalStateException.class, () -> session.send(PROMPT));

        assertTrue(thrown.getMessage().contains("50 byte cap"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains(DetachedTurn.ROOT + "/"), thrown.getMessage());
    }

    @Test
    void aNewTurnClearsOutEarlierTurnDirectories() {
        runtime.plays.add(Play.result("one"));
        runtime.plays.add(Play.result("two"));
        var session = session();
        session.send(PROMPT);
        String first = runtime.launched().getFirst().dir;

        session.send("next");

        assertEquals(List.of(first), runtime.reaped);
        assertEquals(1, runtime.turns.size());
    }

    // ── The attach table ─────────────────────────────────────

    @Nested
    class AfterARestart {

        @Test
        void noRecordedTurnStartsANewOne() {
            runtime.plays.add(Play.result("fresh"));
            assertEquals("fresh", session().send(PROMPT));
            assertEquals(1, runtime.launches);
        }

        @Test
        void sameRequestStillRunningIsAttachedToNotStartedAgain() {
            var recorded = record("t-running", hash(PROMPT), "earlier-session");
            runtime.seed(recorded.id(), Play.result("from the live turn").runningFor(2));

            var session = session();
            String result = session.send(PROMPT);

            assertEquals("from the live turn", result);
            assertEquals(0, runtime.launches, "no second process");
            assertEquals("earlier-session", session.getSessionId(), "adopts the session the turn runs in");
            assertEquals(ActiveTurn.Phase.COLLECTED, state().activeTurn().phase());
        }

        @Test
        void sameRequestAlreadyFinishedIsCollected() {
            var recorded = record("t-done", hash(PROMPT), "s");
            runtime.seed(recorded.id(), Play.result("finished while we were away"));

            assertEquals("finished while we were away", session().send(PROMPT));
            assertEquals(0, runtime.launches);
        }

        @Test
        void sameRequestRecordedButNeverStartedIsStartedUnderItsId() {
            var recorded = record("t-never", hash(PROMPT), "s");
            runtime.plays.add(Play.result("started late"));

            assertEquals("started late", session().send(PROMPT));
            assertEquals(1, runtime.launches);
            assertEquals(DetachedTurn.dir(recorded.id()), runtime.launched().getFirst().dir);
        }

        @Test
        void sameRequestLostIsRelaunchedWithResume() {
            var recorded = record("t-lost", hash(PROMPT), "the-session");
            runtime.seed(recorded.id(), Play.lost("killed"));
            runtime.plays.add(Play.result("second try"));

            assertEquals("second try", session().send(PROMPT));
            var relaunch = runtime.launched().getLast();
            int i = relaunch.argv.indexOf("--resume");
            assertTrue(i > 0, relaunch.argv.toString());
            assertEquals("the-session", relaunch.argv.get(i + 1));
            assertEquals(1, state().activeTurn().attempts());
        }

        @Test
        void aDifferentRequestWhileOneRunsStopsTheOldTurnFirst() {
            var recorded = record("t-stale", hash("an older prompt"), "s");
            runtime.seed(recorded.id(), Play.result("stale").runningFor(100));
            runtime.plays.add(Play.result("new"));

            assertEquals("new", session().send(PROMPT));

            assertEquals(List.of(DetachedTurn.dir(recorded.id())), runtime.cancelled);
            int cancelAt = indexOf(DetachedTurn.CANCEL);
            int launchAt = indexOf(DetachedTurn.LAUNCHER);
            assertTrue(cancelAt >= 0 && cancelAt < launchAt, "cancelled before the new launch");
        }

        @Test
        void aDifferentRequestAfterAFinishedTurnJustStartsANewOne() {
            var recorded = record("t-old", hash("an older prompt"), "s");
            runtime.seed(recorded.id(), Play.result("old"));
            runtime.plays.add(Play.result("new"));

            assertEquals("new", session().send(PROMPT));
            assertTrue(runtime.cancelled.isEmpty());
            assertEquals(1, runtime.launches);
        }

        @Test
        void aCollectedTurnIsNotReusedForTheSamePrompt() {
            var recorded = record("t-collected", hash(PROMPT), "s");
            container().writeState(container().readState().withActiveTurn(recorded.collected()));
            runtime.seed(recorded.id(), Play.result("old result"));
            runtime.plays.add(Play.result("new result"));

            assertEquals("new result", session().send(PROMPT));
            assertEquals(1, runtime.launches);
        }
    }

    // ── Lost turns ───────────────────────────────────────────

    @Test
    void oneLossThenSuccess() {
        runtime.plays.add(Play.lost("oom").runningFor(1));
        runtime.plays.add(Play.result("recovered"));

        assertEquals("recovered", session().send(PROMPT));
        assertEquals(2, runtime.launches);
        assertTrue(runtime.isRunningCalls > 0, "waited for the container before relaunching");
    }

    @Test
    void twoLossesFailNamingTheDirectoryAndTheErrorTail() {
        runtime.plays.add(Play.lost("first death"));
        runtime.plays.add(Play.lost("second death"));

        var thrown = assertThrows(ClaudeTurnLostException.class, () -> session().send(PROMPT));

        assertEquals(2, thrown.getAttempts());
        assertEquals(runtime.launched().getLast().dir, thrown.getTurnDirectory());
        assertTrue(thrown.getMessage().contains("second death"), thrown.getMessage());
        assertEquals(ActiveTurn.Phase.COLLECTED, state().activeTurn().phase());
    }

    @Test
    void noRetriesConfiguredFailsOnTheFirstLoss() {
        ClaudeSession.configureDetached(new ClaudeSession.DetachedSettings(true, Duration.ofSeconds(20), 0));
        runtime.plays.add(Play.lost(""));

        assertThrows(ClaudeTurnLostException.class, () -> session().send(PROMPT));
        assertEquals(1, runtime.launches);
    }

    @Test
    void aLossPastTheDeadlineIsATimeoutNotARelaunch() {
        var recorded = record("t-late", hash(PROMPT), "s");
        runtime.seed(recorded.id(), Play.lost(""));
        clock.advance(Duration.ofMinutes(60).minusSeconds(5));

        assertThrows(ClaudeTimeoutException.class, () -> session().send(PROMPT));
        assertEquals(0, runtime.launches);
    }

    @Test
    void aRelaunchGetsWhatIsLeftOfTheBudget() {
        var recorded = record("t-half", hash(PROMPT), "s");
        runtime.seed(recorded.id(), Play.lost(""));
        runtime.plays.add(Play.result("ok"));
        clock.advance(Duration.ofMinutes(45));

        session().send(PROMPT);

        assertEquals("900s", runtime.launched().getLast().argv.get(2));
    }

    @Test
    void aRelaunchWithoutATranscriptStartsTheSessionInsteadOfResumingIt() {
        runtime.transcriptExists = false;
        runtime.plays.add(Play.lost(""));
        runtime.plays.add(Play.result("ok"));

        var session = session();
        session.send(PROMPT);

        var relaunch = runtime.launched().getLast().argv;
        assertFalse(relaunch.contains("--resume"), relaunch.toString());
        assertEquals(session.getSessionId(), relaunch.get(relaunch.indexOf("--session-id") + 1));
    }

    // ── Observation failures ─────────────────────────────────

    @Test
    void aPermissionErrorFailsAtOnceWithItsCause() {
        runtime.plays.add(Play.result("never seen").runningFor(5));
        runtime.statusFailures.add(
            new ExecResult(
                1,
                "",
                "KubernetesClientException: An error has occurred.; caused by CheckFailedException: Unexpected HTTP response status code 403"
            )
        );

        var thrown = assertThrows(IllegalStateException.class, () -> session().send(PROMPT));

        assertTrue(thrown.getMessage().contains("403"), thrown.getMessage());
        assertEquals(1, runtime.count(DetachedTurn.STATUS));
        assertTrue(sleeps.isEmpty(), "not retried");
    }

    @Test
    void transientFailuresAreRetriedWhileThePodComesBack() {
        runtime.plays.add(Play.result("still there"));
        runtime.statusFailures.add(new ExecResult(1, "", "pods \"t\" not found"));
        runtime.statusFailures.add(new ExecResult(124, "", "Timed out after PT50S"));

        assertEquals("still there", session().send(PROMPT));
        assertEquals(2, sleeps.size());
        assertTrue(runtime.isRunningCalls >= 2, "ensureRunning between attempts");
        assertEquals(1, runtime.launches, "a failed observation never makes a turn lost");
    }

    @Test
    void aContainerThatIsGoneStopsTheWait() {
        runtime.plays.add(Play.result("x").runningFor(100));
        for (int i = 0; i < 5; i++) runtime.statusFailures.add(new ExecResult(1, "", "Error: No such container"));
        runtime.exists = false;

        var thrown = assertThrows(IllegalStateException.class, () -> session().send(PROMPT));
        assertTrue(thrown.getMessage().contains("is gone"), thrown.getMessage());
    }

    // ── Cancel ───────────────────────────────────────────────

    @Test
    void cancelStopsTheRecordedTurnAndMarksItDone() {
        var recorded = record("t-cancel", hash(PROMPT), "s");
        runtime.seed(recorded.id(), Play.result("x").runningFor(100));

        assertTrue(session().cancel());

        assertEquals(List.of(DetachedTurn.dir(recorded.id())), runtime.cancelled);
        assertEquals(ActiveTurn.Phase.COLLECTED, state().activeTurn().phase());
        assertFalse(session().cancel(), "nothing left to stop");
    }

    @Test
    void cancelWithoutAnyTurnIsANoOp() {
        assertFalse(ClaudeSession.cancelActiveTurn(container()));
        assertEquals(0, runtime.count(DetachedTurn.CANCEL));
    }

    @Test
    void aRunningTurnIsVisibleToCallersThatMustNotInterruptIt() {
        var recorded = record("t-busy", hash(PROMPT), "s");
        var turn = runtime.seed(recorded.id(), Play.result("x").runningFor(100));
        assertTrue(session().hasRunningTurn());

        turn.cancelled = true;
        assertFalse(session().hasRunningTurn(), "finished");
        ClaudeSession.configureDetached(ClaudeSession.DetachedSettings.OFF);
        assertFalse(session().hasRunningTurn(), "flag off");
    }

    // ── Flag off ─────────────────────────────────────────────

    @Test
    void withTheFlagOffNothingDetachedHappensAndNoTurnIsRecorded() {
        ClaudeSession.configureDetached(ClaudeSession.DetachedSettings.OFF);
        var direct = new ArrayList<List<String>>();
        var offRuntime = new FakeTurnRuntime() {
            @Override
            public ExecResult exec(
                String name,
                List<String> command,
                Map<String, String> env,
                String workdir,
                byte[] stdin,
                java.time.Duration timeout
            ) {
                if (command.contains("/usr/bin/claude")) {
                    direct.add(command);
                    return new ExecResult(0, "{\"result\":\"attached\"}", "");
                }
                return super.exec(name, command, env, workdir, stdin, timeout);
            }
        };
        var service = new ContainerService(
            dockerConfig(),
            claudeConfig(),
            vcsProviderConfig(),
            botConfig(),
            offRuntime
        );
        var c = new ContainerSession("off", service);
        c.writeState(ContainerState.init("wf", "plan"));

        var s = new ClaudeSession(c, List.of("Read"));
        s.setModel(MODEL);
        assertEquals("attached", s.send(PROMPT));

        assertEquals(1, direct.size());
        assertEquals(
            0,
            offRuntime.count(DetachedTurn.LAUNCHER) +
                offRuntime.count(DetachedTurn.STATUS) +
                offRuntime.count(DetachedTurn.PREPARE)
        );
        String json = new String(offRuntime.files.get("/tmp/smithy-state.json"), StandardCharsets.UTF_8);
        assertFalse(json.contains("activeTurn"), json);
    }

    // ── State and configuration ──────────────────────────────

    @Test
    void stateWithoutAnActiveTurnReadsAsNone() throws Exception {
        String old =
            "{\"sessionId\":\"s\",\"stage\":\"plan\",\"workflow\":\"wf\",\"ciRetryCount\":0,\"ciPaused\":false,\"lastProcessedAt\":\"2026-11-01T00:00:00Z\",\"extraDirs\":[]}";
        runtime.files.put("/tmp/smithy-state.json", old.getBytes(StandardCharsets.UTF_8));

        assertNull(container().readState().activeTurn());
    }

    @Test
    void anActiveTurnSurvivesTheStateFile() {
        var turn = new ActiveTurn(
            "id",
            "hash",
            "s",
            true,
            Instant.parse("2026-11-23T00:00:00Z"),
            Instant.parse("2026-11-23T01:00:00Z"),
            ActiveTurn.Phase.LAUNCHED,
            1
        );
        container().writeState(ContainerState.init("wf", "plan").withActiveTurn(turn).withStage("build"));

        var read = container().readState();
        assertEquals(turn, read.activeTurn());
        assertEquals("build", read.stage());
    }

    @Test
    void configDefaultsAndValidation() {
        var unset = config(null, null, null);
        assertFalse(unset.resolvedDetached());
        assertEquals(Duration.ofSeconds(20), unset.resolvedPollInterval());
        assertEquals(1, unset.resolvedLostTurnRetries());

        var set = config(true, "5s", 0);
        assertTrue(set.resolvedDetached());
        assertEquals(Duration.ofSeconds(5), set.resolvedPollInterval());
        assertEquals(0, set.resolvedLostTurnRetries());

        var zero = assertThrows(IllegalStateException.class, () -> config(true, "0s", null).resolvedPollInterval());
        assertTrue(zero.getMessage().contains("agent.claude.pollInterval"), zero.getMessage());
        assertThrows(IllegalStateException.class, () -> config(true, "soon", null).resolvedPollInterval());
        assertThrows(IllegalStateException.class, () -> config(true, null, -1).resolvedLostTurnRetries());
        assertThrows(IllegalArgumentException.class, () -> new ClaudeSession.DetachedSettings(true, Duration.ZERO, 1));
    }

    @Test
    void theHashSeparatesItsParts() {
        assertNotEquals(
            ClaudeSession.promptHash("ab", "c", "default", MODEL),
            ClaudeSession.promptHash("a", "bc", "default", MODEL)
        );
        assertNotEquals(hash(PROMPT), ClaudeSession.promptHash(PROMPT, null, "plan", MODEL));
        assertEquals(hash(PROMPT), ClaudeSession.promptHash(PROMPT, null, "default", MODEL));
    }

    // ── Plumbing ─────────────────────────────────────────────

    private ContainerSession container() {
        return new ContainerSession("detached-test", containers);
    }

    private ClaudeSession session() {
        var s = new ClaudeSession(container(), List.of("Read"));
        s.setModel(MODEL);
        s.useClock(clock, d -> sleeps.add(d));
        return s;
    }

    private ContainerState state() {
        return container().readState();
    }

    private static String hash(String prompt) {
        return ClaudeSession.promptHash(prompt, null, "default", MODEL);
    }

    private ActiveTurn record(String id, String hash, String sessionId) {
        var turn = new ActiveTurn(
            id,
            hash,
            sessionId,
            true,
            clock.instant(),
            clock.instant().plus(Duration.ofMinutes(60)),
            ActiveTurn.Phase.LAUNCHED,
            0
        );
        container().writeState(container().readState().withActiveTurn(turn));
        return turn;
    }

    private int indexOf(String script) {
        for (int i = 0; i < runtime.execs.size(); i++) {
            var c = runtime.execs.get(i);
            if (c.size() > 2 && c.get(2).equals(script)) return i;
        }
        return -1;
    }

    private static ClaudeAgentConfig config(Boolean detached, String pollInterval, Integer retries) {
        return new ClaudeAgentConfig("claude-opus-5", null, null, null, null, null, detached, pollInterval, retries);
    }

    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static DockerConfig dockerConfig() {
        return new DockerConfig("docker", "smithy-net", "claude-task:test", null);
    }

    private static ClaudeConfig claudeConfig() {
        return new ClaudeConfig("test-token", null, "claude-opus-5");
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
