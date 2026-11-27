package dev.smithyai.orchestrator.service.claude;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.config.KnowledgebaseConfig;
import dev.smithyai.orchestrator.service.claude.dto.SchemaGenerator;
import dev.smithyai.orchestrator.service.docker.ContainerSession;
import dev.smithyai.orchestrator.service.docker.dto.ActiveTurn;
import dev.smithyai.orchestrator.service.docker.dto.ExecResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClaudeSession {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CLAUDE_BINARY = "/usr/bin/claude";
    private static final String PLANS_DIR = "/root/.claude/plans";

    /**
     * Wall-clock budget for one agent turn. A build turn on a real repository
     * works for tens of minutes, so this is deliberately generous; override it
     * with agent.claude.turnTimeout when a workflow needs longer still.
     */
    private static final Duration DEFAULT_TURN_TIMEOUT = Duration.ofMinutes(60);

    /**
     * Head-room on top of the budget before the local docker client stops
     * waiting. The deadline is enforced inside the container, so the client only
     * has to outlive it — this is a backstop for a wedged daemon, not the limit.
     */
    private static final Duration CLIENT_GRACE = Duration.ofSeconds(60);

    /** Grace between SIGTERM and SIGKILL for a turn that overran. */
    private static final String KILL_AFTER = "10s";

    /** GNU timeout's exit status for a command it had to stop. */
    private static final int TIMEOUT_EXIT_CODE = 124;

    private static volatile Duration turnTimeout = DEFAULT_TURN_TIMEOUT;

    /** Timeout of the short control execs of a detached turn (prepare, upload, launch, read). */
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(60);

    /** How long after its deadline a detached turn is still waited for before it is called a timeout. */
    private static final Duration OVERRUN_GRACE = Duration.ofSeconds(10).plus(CLIENT_GRACE);

    /** Below this, a lost turn is reported as a timeout rather than relaunched into a sliver of budget. */
    private static final Duration MIN_RELAUNCH_BUDGET = Duration.ofSeconds(30);

    /** SIGTERM to SIGKILL when a detached turn is stopped. */
    private static final int CANCEL_GRACE_SECONDS = 10;

    /** Default cap on the result a detached turn may hand back. */
    static final long DEFAULT_OUTPUT_CAP = 16L * 1024 * 1024;

    private static final long ERR_CAP = 64L * 1024;
    private static final long LOST_ERR_TAIL = 2L * 1024;

    /** An exec refused for lack of permission: retrying it changes nothing. */
    private static final Pattern PERMANENT_EXEC_FAILURE = Pattern.compile(
        "(?i)(status code (401|403)\\b|\\bforbidden\\b|\\bunauthorized\\b)"
    );

    /**
     * How turns run: over one exec stream (the default), or detached from it.
     *
     * @param enabled         {@code agent.claude.detached}
     * @param pollInterval    longest single observation of a running turn
     * @param lostTurnRetries relaunches of a lost turn before {@link ClaudeTurnLostException}
     */
    public record DetachedSettings(boolean enabled, Duration pollInterval, int lostTurnRetries) {
        public static final DetachedSettings OFF = new DetachedSettings(false, Duration.ofSeconds(20), 1);

        public DetachedSettings {
            if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
                throw new IllegalArgumentException("pollInterval must be positive, got " + pollInterval);
            }
            if (lostTurnRetries < 0) {
                throw new IllegalArgumentException("lostTurnRetries must not be negative, got " + lostTurnRetries);
            }
        }
    }

    private static volatile DetachedSettings detached = DetachedSettings.OFF;

    /** Set once at startup from agent.claude.detached / pollInterval / lostTurnRetries. */
    public static void configureDetached(DetachedSettings settings) {
        detached = settings == null ? DetachedSettings.OFF : settings;
    }

    public static DetachedSettings detachedSettings() {
        return detached;
    }

    /**
     * Set once at startup from agent.claude.turnTimeout by
     * {@link dev.smithyai.orchestrator.config.ConfigLoader}.
     */
    public static void configureTurnTimeout(Duration timeout) {
        if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
            turnTimeout = timeout;
        }
    }

    public static Duration turnTimeout() {
        return turnTimeout;
    }

    /**
     * Model used when callers don't pass one explicitly. Set once at startup from
     * claude.model (CLAUDE_MODEL) by {@link dev.smithyai.orchestrator.config.ConfigLoader}.
     */
    private static volatile String defaultModel = "opus";

    public static void configureDefaultModel(String model) {
        if (model != null && !model.isBlank()) {
            defaultModel = model;
        }
    }

    /** Not final: attaching to a detached turn adopts the session that turn runs in. */
    @Getter
    private String sessionId;

    private Clock clock = Clock.systemUTC();
    private Sleeper sleeper = Thread::sleep;
    private long outputCap = DEFAULT_OUTPUT_CAP;

    private final ContainerSession container;
    private final List<String> tools;
    private final KnowledgebaseConfig knowledgebaseConfig;
    private String contextRepoName;
    private String model;
    private List<String> addDirs = List.of();
    private boolean started = false;
    private Duration sessionTurnTimeout;

    public ClaudeSession(ContainerSession container, List<String> tools) {
        this(container, tools, null, null);
    }

    public ClaudeSession(ContainerSession container, List<String> tools, String existingSessionId) {
        this(container, tools, existingSessionId, null);
    }

    public ClaudeSession(ContainerSession container, List<String> tools, KnowledgebaseConfig knowledgebaseConfig) {
        this(container, tools, null, knowledgebaseConfig);
    }

    public ClaudeSession(
        ContainerSession container,
        List<String> tools,
        String existingSessionId,
        KnowledgebaseConfig knowledgebaseConfig
    ) {
        this.sessionId = existingSessionId != null ? existingSessionId : UUID.randomUUID().toString();
        this.container = container;
        this.tools = tools;
        this.knowledgebaseConfig = knowledgebaseConfig;
        this.started = existingSessionId != null;
    }

    /** Test seams: time, waiting, and the result cap of detached turns. */
    void useClock(Clock clock, Sleeper sleeper) {
        this.clock = clock;
        this.sleeper = sleeper;
    }

    void useOutputCap(long bytes) {
        this.outputCap = bytes;
    }

    public void setContextRepoName(String contextRepoName) {
        this.contextRepoName = contextRepoName;
    }

    /** Run this session's turns on {@code model} instead of the configured default. Blank keeps the default. */
    public void setModel(String model) {
        if (model != null && !model.isBlank()) {
            this.model = model;
        }
    }

    /**
     * Directories outside the workspace the agent may read and edit —
     * a context repo, extra checkouts. Claude scopes file access to its
     * working directory; anything beyond it must be named per turn or every
     * access is a permission prompt, which headless runs auto-deny.
     */
    public void setAddDirs(List<String> dirs) {
        this.addDirs = dirs == null ? List.of() : List.copyOf(dirs);
    }

    /**
     * Give this session's turns a different budget from the configured one — a
     * turn someone is waiting on in a browser is not a build turn. Null or
     * non-positive keeps the configured budget.
     */
    public void setTurnTimeout(Duration timeout) {
        if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
            this.sessionTurnTimeout = timeout;
        }
    }

    private String model() {
        return model != null ? model : defaultModel;
    }

    private Duration budget() {
        return sessionTurnTimeout != null ? sessionTurnTimeout : turnTimeout;
    }

    public void startPlan(String prompt) {
        execute(prompt, model(), "plan", false, null);
        started = true;
    }

    public String send(String prompt) {
        return send(prompt, String.class);
    }

    public <T> T send(String prompt, Class<T> resultType) {
        return send(prompt, resultType, model());
    }

    public <T> T send(String prompt, Class<T> resultType, String model) {
        boolean resume = started;
        if (!started) started = true;

        String schema = null;
        if (!resultType.equals(String.class)) {
            schema = SchemaGenerator.generate(resultType);
        }

        String content = execute(prompt, model, "default", resume, schema);

        if (resultType.equals(String.class)) {
            return resultType.cast(content);
        }

        try {
            return MAPPER.readValue(content.strip(), resultType);
        } catch (Exception e) {
            throw new ClaudeParseException(
                "Failed to parse Claude output as " + resultType.getSimpleName(),
                content,
                e
            );
        }
    }

    /**
     * Ask for a structured answer against a schema built at runtime.
     *
     * <p>{@link #send(String, Class)} generates its schema from a Java DTO, which
     * a YAML workflow cannot name. A definition declares the shape it wants
     * instead, and gets back the parsed object.
     */
    public Map<String, Object> sendStructured(String prompt, String jsonSchema) {
        boolean resume = started;
        started = true;
        String content = execute(prompt, model(), "default", resume, jsonSchema);
        try {
            return MAPPER.readValue(content.strip(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new ClaudeParseException("Failed to parse Claude output against the declared schema", content, e);
        }
    }

    public void ensureCommitted() {
        String status = container.exec("sh", "-c", "git status --porcelain").stdout().strip();
        if (status.isEmpty()) return;

        log.info("Uncommitted changes in {}, asking Claude to commit", container.getContainerName());
        send("Please commit all changes with an appropriate commit message.");
    }

    /**
     * Returns the path of the latest plan file inside the container,
     * or empty if none exists.
     */
    public Optional<String> latestPlanFile() {
        try {
            String listing = container
                .exec("sh", "-c", "ls -t \"" + PLANS_DIR + "\"/*.md 2>/dev/null")
                .stdout()
                .strip();
            return listing
                .lines()
                .findFirst()
                .map(String::strip)
                .filter(p -> !p.isBlank());
        } catch (Exception e) {
            log.warn("No plan files found in {} on {}", PLANS_DIR, container.getContainerName());
            return Optional.empty();
        }
    }

    // ── Internal ─────────────────────────────────────────────

    private String execute(String prompt, String model, String permissionMode, boolean resume, String outputSchema) {
        Duration budget = budget();
        if (detached.enabled()) {
            return executeDetached(prompt, model, permissionMode, resume, outputSchema, budget);
        }

        List<String> command = argv(model, permissionMode, resume, outputSchema, budget);
        log.debug("Executing Claude prompt on {} (session={})", container.getContainerName(), sessionId);
        ExecResult result = container.exec(command, budget.plus(CLIENT_GRACE), prompt);
        return interpret(result.exitCode(), result.stdout(), result.stderr(), outputSchema, budget);
    }

    /** The command line of one turn, {@code timeout} prefix included. The prompt arrives on stdin. */
    private List<String> argv(
        String model,
        String permissionMode,
        boolean resume,
        String outputSchema,
        Duration budget
    ) {
        List<String> command = new ArrayList<>();
        // Bound the turn inside the container rather than only here. Killing the
        // local `docker exec` leaves the CLI running: the daemon does not stop an
        // exec whose client went away, and the orphan keeps working and keeps
        // writing the session transcript that the next turn resumes.
        command.add("timeout");
        command.add("--kill-after=" + KILL_AFTER);
        command.add(Math.max(1, budget.toSeconds()) + "s");
        command.add(CLAUDE_BINARY);
        command.add("-p");
        command.add("-"); // read prompt from stdin
        command.add("--model");
        command.add(model);
        command.add("--output-format");
        command.add("json");
        if (resume) {
            command.add("--resume");
            command.add(sessionId);
        } else {
            command.add("--session-id");
            command.add(sessionId);
        }
        command.add("--permission-mode");
        command.add(permissionMode);
        command.add("--max-turns");
        command.add("200");

        if (tools != null && !tools.isEmpty()) {
            command.add("--allowedTools");
            command.add(String.join(",", tools));
        } else {
            // Worth saying out loud: with no allowlist a headless turn on default
            // permissions refuses every tool call before running it, so the agent
            // reports that it cannot read a file or push a branch and looks broken
            // rather than unprivileged.
            log.warn(
                "No tools allowed for the turn on {} (session={}); every tool call will be refused",
                container.getContainerName(),
                sessionId
            );
        }

        for (String dir : addDirs) {
            command.add("--add-dir");
            command.add(dir);
        }

        if (outputSchema != null) {
            command.add("--json-schema");
            command.add(outputSchema);
        }

        if (knowledgebaseConfig != null && knowledgebaseConfig.isActive() && contextRepoName != null) {
            log.info("Adding knowledgebase MCP config for context repo: {}", contextRepoName);
            command.add("--mcp-config");
            command.add(knowledgebaseConfig.mcpConfigJson(contextRepoName));
        } else {
            log.debug(
                "Knowledgebase MCP not added: config={}, active={}, contextRepo={}",
                knowledgebaseConfig != null,
                knowledgebaseConfig != null && knowledgebaseConfig.isActive(),
                contextRepoName
            );
        }
        return command;
    }

    /**
     * What a finished turn means, whichever way it ran: 124 is the budget, any
     * other nonzero status a failure carrying stderr, zero a JSON result.
     */
    private String interpret(int exitCode, String stdout, String stderr, String outputSchema, Duration budget) {
        if (exitCode == TIMEOUT_EXIT_CODE) {
            log.warn(
                "Claude turn timed out after {} on {} (session={})",
                budget,
                container.getContainerName(),
                sessionId
            );
            throw new ClaudeTimeoutException(budget, container.getContainerName(), sessionId, stdout);
        }

        if (exitCode != 0) {
            log.warn(
                "Claude process failed: exitCode={}, stderr={}, stdout={}",
                exitCode,
                stderr,
                stdout.length() > 500 ? stdout.substring(0, 500) + "..." : stdout
            );
            throw new IllegalStateException("Claude process exited with code %d: %s".formatted(exitCode, stderr));
        }

        log.debug("Claude response on {}: {}", container.getContainerName(), stdout);

        try {
            JsonNode root = MAPPER.readTree(stdout);
            // When --json-schema is used, structured output is in "structured_output", not "result"
            if (outputSchema != null && root.has("structured_output")) {
                return MAPPER.writeValueAsString(root.get("structured_output"));
            }
            if (root.has("result")) {
                return root.get("result").asText();
            }
            throw new IllegalStateException("Claude JSON response missing 'result' field: " + stdout);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Claude JSON response: " + stdout, e);
        }
    }

    // ── Detached turns ───────────────────────────────────────

    /**
     * Run the turn as a process that outlives every exec, then wait for it with
     * short observations. See {@code openspec/changes/add-detached-claude-turns}.
     *
     * <p>Before starting anything, the recorded turn is consulted: the same
     * request still in flight (a step re-run after a restart) is attached to,
     * a different one is stopped first, so a session never has two turns.
     */
    private String executeDetached(
        String prompt,
        String model,
        String permissionMode,
        boolean resume,
        String outputSchema,
        Duration budget
    ) {
        String hash = promptHash(prompt, outputSchema, permissionMode, model);
        ActiveTurn recorded = recordedTurn();
        if (recorded != null && recorded.isLaunched()) {
            var found = observe(recorded, 0);
            if (hash.equals(recorded.promptHash())) {
                log.info(
                    "Attaching to Claude turn {} on {} (session={}, found {})",
                    recorded.id(),
                    container.getContainerName(),
                    recorded.sessionId(),
                    found.getClass().getSimpleName()
                );
                // The turn runs in the session it was started with, which a
                // fresh ClaudeSession after a restart does not know.
                if (recorded.sessionId() != null) sessionId = recorded.sessionId();
                started = true;
                if (found instanceof DetachedTurn.Status.None) {
                    // Recorded, never started: the orchestrator stopped between
                    // the state write and the launch.
                    Duration remaining = remaining(recorded);
                    launch(recorded, prompt, argv(model, permissionMode, recorded.resume(), outputSchema, remaining));
                }
                return await(recorded, prompt, model, permissionMode, outputSchema);
            }
            if (found instanceof DetachedTurn.Status.Running) {
                log.warn(
                    "Claude turn {} on {} is still running for a different request; stopping it first",
                    recorded.id(),
                    container.getContainerName()
                );
                stop(recorded);
            }
        }

        Instant now = clock.instant();
        var turn = new ActiveTurn(
            UUID.randomUUID().toString(),
            hash,
            sessionId,
            resume,
            now,
            now.plus(budget),
            ActiveTurn.Phase.LAUNCHED,
            0
        );
        launch(turn, prompt, argv(model, permissionMode, resume, outputSchema, budget));
        return await(turn, prompt, model, permissionMode, outputSchema);
    }

    /**
     * Clear out earlier turns, upload the prompt, record the turn, start it.
     * Recorded before started: a crash in between leaves a turn with no pid,
     * which the next attach starts (see {@link DetachedTurn.Status.None}).
     */
    private void launch(ActiveTurn turn, String prompt, List<String> argv) {
        String dir = DetachedTurn.dir(turn.id());
        requireOk(
            container.exec(DetachedTurn.prepare(turn.id(), CANCEL_GRACE_SECONDS), CONTROL_TIMEOUT, null),
            "prepare " + dir
        );
        requireOk(
            container.exec(DetachedTurn.writePrivate(dir + "/prompt"), CONTROL_TIMEOUT, prompt),
            "upload the prompt to " + dir
        );
        recordTurn(turn);
        log.info(
            "Starting detached Claude turn {} on {} (session={}, attempt {})",
            turn.id(),
            container.getContainerName(),
            turn.sessionId(),
            turn.attempts() + 1
        );
        requireOk(
            container.exec(DetachedTurn.launch(turn.id(), argv), CONTROL_TIMEOUT, null),
            "start the turn in " + dir
        );
    }

    /**
     * Observe until the turn finishes, relaunching it after a loss while the
     * budget and {@code lostTurnRetries} allow.
     */
    private String await(ActiveTurn turn, String prompt, String model, String permissionMode, String outputSchema) {
        int pollSeconds = (int) Math.max(1, detached.pollInterval().toSeconds());
        while (true) {
            if (clock.instant().isAfter(turn.deadline().plus(OVERRUN_GRACE))) {
                // The in-container timeout should have ended it long ago and
                // nobody saw an rc: stop what is left and call it what it is.
                stop(turn);
                recordTurn(turn.collected());
                throw new ClaudeTimeoutException(budgetOf(turn), container.getContainerName(), sessionId, "");
            }
            var status = observe(turn, pollSeconds);
            switch (status) {
                case DetachedTurn.Status.Running running -> {
                    // next observation
                }
                case DetachedTurn.Status.Done done -> {
                    return collect(turn, done.exitCode(), outputSchema);
                }
                case DetachedTurn.Status.Lost lost -> turn = relaunchAfterLoss(
                    turn,
                    prompt,
                    model,
                    permissionMode,
                    outputSchema
                );
                // Launched before, and now not even the directory is left: the
                // volume was replaced. Nothing to wait for, same as lost.
                case DetachedTurn.Status.None none -> turn = relaunchAfterLoss(
                    turn,
                    prompt,
                    model,
                    permissionMode,
                    outputSchema
                );
            }
        }
    }

    /**
     * One status of the turn, retrying failed execs. A failure that is about
     * permission fails at once: retrying a 403 only hides it until the deadline.
     * Anything else (pod being recreated, API server restart, network) is waited
     * out, bringing the container back up in between, and never makes the turn
     * lost: only a successful look at a free lock without an rc does that.
     */
    private DetachedTurn.Status observe(ActiveTurn turn, int waitSeconds) {
        int failures = 0;
        while (true) {
            var result = container.exec(
                DetachedTurn.status(turn.id(), waitSeconds),
                Duration.ofSeconds(waitSeconds).plus(CONTROL_TIMEOUT),
                null
            );
            String problem;
            if (result.exitCode() == 0) {
                try {
                    return DetachedTurn.parse(result.stdout());
                } catch (IllegalStateException e) {
                    problem = e.getMessage();
                }
            } else {
                problem = "exit " + result.exitCode() + ": " + result.stderr().strip();
                if (PERMANENT_EXEC_FAILURE.matcher(result.stderr()).find()) {
                    throw new IllegalStateException(
                        "Cannot observe Claude turn %s on %s: %s".formatted(
                            turn.id(),
                            container.getContainerName(),
                            problem
                        )
                    );
                }
            }
            failures++;
            if (clock.instant().isAfter(turn.deadline().plus(OVERRUN_GRACE))) {
                throw new IllegalStateException(
                    "Lost sight of Claude turn %s on %s past its deadline: %s".formatted(
                        turn.id(),
                        container.getContainerName(),
                        problem
                    )
                );
            }
            if (failures >= 3 && !container.exists()) {
                throw new IllegalStateException(
                    "Container %s is gone while observing Claude turn %s: %s".formatted(
                        container.getContainerName(),
                        turn.id(),
                        problem
                    )
                );
            }
            log.warn(
                "Observing Claude turn {} on {} failed ({}), retrying: {}",
                turn.id(),
                container.getContainerName(),
                failures,
                problem
            );
            container.ensureRunning();
            pause(backoff(failures));
        }
    }

    private String collect(ActiveTurn turn, int exitCode, String outputSchema) {
        String dir = DetachedTurn.dir(turn.id());
        var out = container.exec(DetachedTurn.head(dir + "/out", outputCap + 1), CONTROL_TIMEOUT, null);
        requireOk(out, "read " + dir + "/out");
        var err = container.exec(DetachedTurn.tail(dir + "/err", ERR_CAP), CONTROL_TIMEOUT, null);
        recordTurn(turn.collected());
        if (out.stdout().getBytes(StandardCharsets.UTF_8).length > outputCap) {
            throw new IllegalStateException(
                "Claude result in %s/out on %s exceeds the %d byte cap; it was not read".formatted(
                    dir,
                    container.getContainerName(),
                    outputCap
                )
            );
        }
        return interpret(exitCode, out.stdout(), err.exitCode() == 0 ? err.stdout() : "", outputSchema, budgetOf(turn));
    }

    private ActiveTurn relaunchAfterLoss(
        ActiveTurn turn,
        String prompt,
        String model,
        String permissionMode,
        String outputSchema
    ) {
        String dir = DetachedTurn.dir(turn.id());
        var tail = container.exec(DetachedTurn.tail(dir + "/err", LOST_ERR_TAIL), CONTROL_TIMEOUT, null);
        String errTail = tail.exitCode() == 0 ? tail.stdout() : "";
        if (turn.attempts() >= detached.lostTurnRetries()) {
            recordTurn(turn.collected());
            log.warn("Claude turn {} on {} lost again, giving up", turn.id(), container.getContainerName());
            throw new ClaudeTurnLostException(container.getContainerName(), dir, turn.attempts() + 1, errTail);
        }
        Duration remaining = remaining(turn);
        if (remaining.compareTo(MIN_RELAUNCH_BUDGET) < 0) {
            recordTurn(turn.collected());
            throw new ClaudeTimeoutException(budgetOf(turn), container.getContainerName(), sessionId, "");
        }
        // The pod may be on its way back (a Sandbox controller recreates it
        // with backoff); a relaunch into a missing pod fails for the wrong reason.
        container.ensureRunning();
        boolean resume = transcriptExists(turn.sessionId());
        var next = turn.relaunched(UUID.randomUUID().toString(), resume);
        log.warn(
            "Claude turn {} on {} was lost; relaunching as {} with {} left ({})",
            turn.id(),
            container.getContainerName(),
            next.id(),
            remaining,
            resume ? "--resume" : "new session"
        );
        launch(next, prompt, argv(model, permissionMode, resume, outputSchema, remaining));
        return next;
    }

    /**
     * Whether a detached turn is running on this container right now. A caller
     * that must not interrupt one (a person's takeover message) asks this first,
     * because the attach rules would otherwise stop a running turn for a
     * different prompt. Always false with detached turns off.
     */
    public boolean hasRunningTurn() {
        if (!detached.enabled()) return false;
        ActiveTurn turn = recordedTurn();
        if (turn == null || !turn.isLaunched()) return false;
        var result = container.exec(DetachedTurn.status(turn.id(), 0), CONTROL_TIMEOUT, null);
        if (result.exitCode() != 0) return false;
        try {
            return DetachedTurn.parse(result.stdout()) instanceof DetachedTurn.Status.Running;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /**
     * Stop this session's recorded turn, if one is running, and mark it done.
     * Returns whether there was one to stop. Safe to call when detached turns
     * are off or the container has no state.
     */
    public boolean cancel() {
        return cancelActiveTurn(container);
    }

    /** {@link #cancel()} for a container whose ClaudeSession is not at hand (the run is being cancelled). */
    public static boolean cancelActiveTurn(ContainerSession container) {
        ActiveTurn turn;
        try {
            turn = container.readState().activeTurn();
        } catch (RuntimeException e) {
            return false;
        }
        if (turn == null || !turn.isLaunched()) return false;
        var result = container.exec(DetachedTurn.cancel(turn.id(), CANCEL_GRACE_SECONDS), CONTROL_TIMEOUT, null);
        if (result.exitCode() != 0) {
            log.warn(
                "Could not stop Claude turn {} on {}: {}",
                turn.id(),
                container.getContainerName(),
                result.stderr()
            );
            return false;
        }
        container.writeState(container.readState().withActiveTurn(turn.collected()));
        log.info("Stopped Claude turn {} on {}", turn.id(), container.getContainerName());
        return true;
    }

    private void stop(ActiveTurn turn) {
        var result = container.exec(DetachedTurn.cancel(turn.id(), CANCEL_GRACE_SECONDS), CONTROL_TIMEOUT, null);
        if (result.exitCode() != 0) {
            throw new IllegalStateException(
                "Could not stop Claude turn %s on %s: %s".formatted(
                    turn.id(),
                    container.getContainerName(),
                    result.stderr()
                )
            );
        }
    }

    private ActiveTurn recordedTurn() {
        try {
            return container.readState().activeTurn();
        } catch (RuntimeException e) {
            // No state yet (a session on a bare container in a test, or a
            // container that lost its file): nothing recorded.
            return null;
        }
    }

    /** Read-modify-write from the file, not the cache: another session may have moved the stage since. */
    private void recordTurn(ActiveTurn turn) {
        container.writeState(container.readState().withActiveTurn(turn));
    }

    private boolean transcriptExists(String session) {
        if (session == null) return false;
        var found = container.exec(
            List.of("sh", "-c", "find /root/.claude/projects -name \"$0.jsonl\" 2>/dev/null | grep -q .", session),
            CONTROL_TIMEOUT,
            null
        );
        return found.exitCode() == 0;
    }

    private Duration remaining(ActiveTurn turn) {
        Duration left = Duration.between(clock.instant(), turn.deadline());
        return left.isNegative() ? Duration.ZERO : left;
    }

    private static Duration budgetOf(ActiveTurn turn) {
        return Duration.between(turn.startedAt(), turn.deadline());
    }

    private Duration backoff(int failures) {
        long seconds = Math.min(detached.pollInterval().toSeconds(), 1L << Math.min(failures - 1, 5));
        return Duration.ofSeconds(Math.max(1, seconds));
    }

    private void pause(Duration duration) {
        try {
            sleeper.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // The turn itself is unaffected; whoever runs this step next attaches to it.
            throw new IllegalStateException(
                "Interrupted while waiting for a Claude turn on " + container.getContainerName(),
                e
            );
        }
    }

    private void requireOk(ExecResult result, String what) {
        if (result.exitCode() != 0) {
            throw new IllegalStateException(
                "Could not %s on %s (exit %d): %s".formatted(
                    what,
                    container.getContainerName(),
                    result.exitCode(),
                    result.stderr()
                )
            );
        }
    }

    /** Identity of a request: the same prompt, schema, permission mode and model is the same turn. */
    static String promptHash(String prompt, String outputSchema, String permissionMode, String model) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String part : new String[] { prompt, outputSchema, permissionMode, model }) {
                digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Test seam for the retry backoff. */
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
