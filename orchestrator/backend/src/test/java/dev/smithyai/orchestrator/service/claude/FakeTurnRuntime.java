package dev.smithyai.orchestrator.service.claude;

import dev.smithyai.orchestrator.service.docker.ContainerRuntime;
import dev.smithyai.orchestrator.service.docker.dto.ContainerSpec;
import dev.smithyai.orchestrator.service.docker.dto.ExecResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A container that understands the detached-turn scripts by name and plays
 * scripted turns, so {@link ClaudeSession}'s bookkeeping can be tested without
 * a shell. The scripts themselves are tested for real in
 * {@link DetachedTurnScriptTest}.
 */
class FakeTurnRuntime implements ContainerRuntime {

    /** What a launched turn does: stay running for some observations, then end. */
    record Play(int runningObservations, boolean lost, int exitCode, String out, String err) {
        static Play done(String out) {
            return new Play(0, false, 0, out, "");
        }

        static Play result(String text) {
            return done("{\"result\":\"" + text + "\"}");
        }

        static Play exit(int code, String err) {
            return new Play(0, false, code, "", err);
        }

        static Play lost(String err) {
            return new Play(0, true, 0, "", err);
        }

        Play runningFor(int observations) {
            return new Play(observations, lost, exitCode, out, err);
        }
    }

    static final class Turn {

        final String dir;
        final List<String> argv;
        final String prompt;
        final Play play;
        int observations;
        boolean cancelled;

        Turn(String dir, List<String> argv, String prompt, Play play) {
            this.dir = dir;
            this.argv = argv;
            this.prompt = prompt;
            this.play = play;
        }

        boolean running() {
            return !cancelled && observations < play.runningObservations;
        }
    }

    final Map<String, byte[]> files = new HashMap<>();
    final Map<String, Turn> turns = new LinkedHashMap<>();
    final Deque<Play> plays = new ArrayDeque<>();
    final List<List<String>> execs = new ArrayList<>();
    final List<String> cancelled = new ArrayList<>();
    final List<String> reaped = new ArrayList<>();
    final Deque<ExecResult> statusFailures = new ArrayDeque<>();
    int launches;
    int isRunningCalls;
    boolean transcriptExists = true;
    boolean exists = true;

    /** Make {@code dir} hold a turn as if an earlier orchestrator had launched it. */
    Turn seed(String turnId, Play play) {
        var turn = new Turn(DetachedTurn.dir(turnId), List.of(), "", play);
        turns.put(turn.dir, turn);
        return turn;
    }

    List<Turn> launched() {
        return List.copyOf(turns.values());
    }

    long count(String script) {
        return execs
            .stream()
            .filter(c -> c.size() > 2 && c.get(2).equals(script))
            .count();
    }

    @Override
    public ExecResult exec(
        String name,
        List<String> command,
        Map<String, String> environment,
        String workdir,
        byte[] stdin,
        Duration timeout
    ) {
        execs.add(List.copyOf(command));
        String script = command.size() > 2 && command.get(0).equals("sh") ? command.get(2) : "";
        String arg0 = command.size() > 3 ? command.get(3) : null;
        if (script.equals(DetachedTurn.WRITE_PRIVATE) || script.startsWith("cat > ")) {
            String path = script.startsWith("cat > ") ? script.substring(7, script.length() - 1) : arg0;
            files.put(path, stdin == null ? new byte[0] : stdin);
            return ok("");
        }
        if (script.equals(DetachedTurn.PREPARE)) {
            for (var it = turns.values().iterator(); it.hasNext(); ) {
                var t = it.next();
                if (t.dir.equals(arg0)) continue;
                if (t.running()) cancelled.add(t.dir);
                reaped.add(t.dir);
                it.remove();
            }
            return ok("");
        }
        if (script.equals(DetachedTurn.LAUNCHER)) {
            if (turns.containsKey(arg0) && turns.get(arg0).argv.size() > 0) return ok("LAUNCHED\n");
            byte[] prompt = files.get(arg0 + "/prompt");
            if (prompt == null) return new ExecResult(2, "", "no prompt in " + arg0);
            if (plays.isEmpty()) throw new AssertionError(
                "a turn was launched that the test did not expect: " + command
            );
            launches++;
            turns.put(
                arg0,
                new Turn(
                    arg0,
                    List.copyOf(command.subList(4, command.size())),
                    new String(prompt, StandardCharsets.UTF_8),
                    plays.poll()
                )
            );
            return ok("LAUNCHED\n");
        }
        if (script.equals(DetachedTurn.STATUS)) {
            if (!statusFailures.isEmpty()) return statusFailures.poll();
            var t = turns.get(arg0);
            if (t == null || (t.argv.isEmpty() && t.play == null)) return ok("NONE\n");
            if (t.cancelled) return ok("DONE 143\n");
            if (t.observations < t.play.runningObservations) {
                t.observations++;
                return ok("RUNNING\n");
            }
            return ok(t.play.lost ? "LOST\n" : "DONE " + t.play.exitCode + "\n");
        }
        if (script.equals(DetachedTurn.CANCEL)) {
            var t = turns.get(arg0);
            if (t != null && t.running()) {
                t.cancelled = true;
                cancelled.add(t.dir);
            }
            return ok("");
        }
        if (script.equals(DetachedTurn.HEAD) || script.equals(DetachedTurn.TAIL)) {
            var t = turns.get(arg0.substring(0, arg0.lastIndexOf('/')));
            if (t == null) return ok("");
            String content = arg0.endsWith("/out") ? t.play.out : t.play.err;
            int cap = Integer.parseInt(command.get(4));
            return ok(content.length() > cap ? content.substring(0, cap) : content);
        }
        if (script.contains("/root/.claude/projects")) {
            return new ExecResult(transcriptExists ? 0 : 1, "", "");
        }
        throw new AssertionError("unexpected exec: " + command);
    }

    @Override
    public byte[] execForBytes(String name, List<String> command, Duration timeout) {
        execs.add(List.copyOf(command));
        if (command.size() == 2 && command.get(0).equals("cat")) {
            byte[] data = files.get(command.get(1));
            if (data == null) throw new RuntimeException("cat: " + command.get(1) + ": No such file or directory");
            return data;
        }
        throw new AssertionError("unexpected execForBytes: " + command);
    }

    private static ExecResult ok(String out) {
        return new ExecResult(0, out, "");
    }

    @Override
    public void create(ContainerSpec spec) {}

    @Override
    public boolean exists(String name) {
        return exists;
    }

    @Override
    public Optional<Boolean> isRunning(String name) {
        isRunningCalls++;
        return Optional.of(true);
    }

    @Override
    public boolean start(String name) {
        return true;
    }

    @Override
    public List<String> listManaged(boolean includeStopped) {
        return List.of();
    }

    @Override
    public String logs(String name, int tailLines) {
        return "";
    }

    @Override
    public String ownLogs(int tailLines) {
        return "";
    }

    @Override
    public void remove(String name) {}
}
