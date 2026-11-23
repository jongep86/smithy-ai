package dev.smithyai.orchestrator.service.claude;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The in-container half of a detached Claude turn: where its files live, and the
 * shell that starts, observes and stops it.
 *
 * <p>A turn is a directory under {@value #ROOT}:
 *
 * <pre>
 *   prompt   the prompt, uploaded before launch, read by claude as stdin
 *   cmd      the argv, one argument per line (for a human, never re-executed)
 *   out/err  claude's stdout and stderr
 *   lock     held with flock for as long as any process of the turn lives
 *   pid      pid of the timeout process, which leads the turn's process group
 *   rc       exit status, renamed into place as the last action
 * </pre>
 *
 * <p>Every script is a constant. Paths and arguments travel as positional
 * parameters ({@code $0}, {@code $1}, {@code "$@"}), so nothing a caller passes
 * is ever spliced into shell source; that is what makes prompts, schemas and
 * MCP configs with quotes and newlines safe here.
 *
 * <p>Detachment rests on three things the spike in
 * {@code openspec/changes/add-detached-claude-turns} showed on Docker and on
 * Kubernetes: {@code setsid} puts the turn in its own session, its stdio is on
 * files rather than the exec stream, and the lock is inherited by every process
 * of the turn, so it drops only when the last of them is gone.
 */
public final class DetachedTurn {

    public static final String ROOT = "/tmp/smithy-turns";

    /**
     * Start a turn and return as soon as it runs. {@code $0} is the turn
     * directory, {@code "$@"} the full argv (starting with {@code timeout}).
     *
     * <p>Exits 0 with {@code LAUNCHED} once the pid file exists (or the turn has
     * already finished). A second launcher for the same directory finds the lock
     * taken, starts nothing, and also reports {@code LAUNCHED}: launching is
     * idempotent per turn id.
     */
    static final String LAUNCHER = """
        d="$0"
        umask 077
        mkdir -p "$d" || exit 2
        chmod 700 "$d"
        [ -f "$d/prompt" ] || { echo "no prompt in $d" >&2; exit 2; }
        printf '%s\\n' "$@" > "$d/cmd"
        setsid sh -c '
        d="$0"
        exec 9>"$d/lock"
        flock -n 9 || exit 99
        "$@" <"$d/prompt" >"$d/out" 2>"$d/err" &
        echo $! >"$d/pid"
        wait $!
        echo $? >"$d/rc.tmp"
        mv "$d/rc.tmp" "$d/rc"' "$d" "$@" >/dev/null 2>&1 </dev/null &
        i=0
        while [ ! -f "$d/pid" ] && [ ! -f "$d/rc" ]; do
          i=$((i+1))
          [ $i -gt 150 ] && { echo "turn in $d did not start within 15s" >&2; exit 3; }
          sleep 0.1
        done
        echo LAUNCHED
        """;

    /**
     * One observation, at most {@code $1} seconds long. {@code $0} is the turn
     * directory. Prints exactly one of {@code NONE}, {@code RUNNING},
     * {@code DONE <rc>} or {@code LOST}.
     *
     * <p>{@code NONE}: never launched (no pid, no rc). {@code LOST}: launched,
     * the lock is free and there is no rc, so every process of the turn is gone
     * without finishing.
     */
    static final String STATUS = """
        d="$0"; n="$1"; i=0
        [ -f "$d/pid" ] || [ -f "$d/rc" ] || { echo NONE; exit 0; }
        while [ ! -f "$d/rc" ] && [ $i -lt "$n" ]; do
          if flock -n "$d/lock" true 2>/dev/null && [ ! -f "$d/rc" ]; then echo LOST; exit 0; fi
          sleep 1; i=$((i+1))
        done
        if [ -f "$d/rc" ]; then echo "DONE $(cat "$d/rc")"; else echo RUNNING; fi
        """;

    /**
     * Stop the turn in {@code $0}: SIGTERM to its process group, SIGKILL after
     * {@code $1} seconds, then wait for the lock. Exits 0 once nothing of the
     * turn runs; that is also the result for a turn that was not running.
     */
    static final String CANCEL = """
        d="$0"; n="$1"
        [ -f "$d/pid" ] || exit 0
        flock -n "$d/lock" true 2>/dev/null && exit 0
        p=$(cat "$d/pid")
        kill -TERM -- "-$p" 2>/dev/null || kill -TERM "$p" 2>/dev/null
        i=0
        while ! flock -n "$d/lock" true 2>/dev/null; do
          i=$((i+1))
          if [ $i -eq "$n" ]; then kill -KILL -- "-$p" 2>/dev/null || kill -KILL "$p" 2>/dev/null; fi
          [ $i -gt $((n+10)) ] && { echo "turn in $d still holds its lock" >&2; exit 1; }
          sleep 1
        done
        exit 0
        """;

    /**
     * Make room for the turn in {@code $0}: stop and delete every other turn
     * directory, then create this one, private. One live turn per container;
     * the directory of the previous turn stays inspectable until this point.
     */
    static final String PREPARE = """
        d="$0"; n="$1"
        umask 077
        mkdir -p "$(dirname "$d")"
        for o in "$(dirname "$d")"/*; do
          [ -d "$o" ] || continue
          [ "$o" = "$d" ] && continue
          if [ -f "$o/pid" ] && ! flock -n "$o/lock" true 2>/dev/null; then
            p=$(cat "$o/pid")
            kill -TERM -- "-$p" 2>/dev/null || kill -TERM "$p" 2>/dev/null
            i=0
            while ! flock -n "$o/lock" true 2>/dev/null; do
              i=$((i+1)); [ $i -ge "$n" ] && { kill -KILL -- "-$p" 2>/dev/null; sleep 1; break; }
              sleep 1
            done
          fi
          rm -rf "$o"
        done
        mkdir -p "$d" && chmod 700 "$d"
        """;

    /** Write stdin to {@code $0} readable only by its owner. */
    static final String WRITE_PRIVATE = "umask 077; cat > \"$0\"";

    /**
     * The first {@code $1} bytes of the file {@code $0}, nothing if it is
     * absent. Reading one byte past a cap is how the caller sees it was hit.
     */
    static final String HEAD = "[ -f \"$0\" ] && head -c \"$1\" \"$0\"; exit 0";

    /** The last {@code $1} bytes of the file {@code $0}, nothing if it is absent. */
    static final String TAIL = "[ -f \"$0\" ] && tail -c \"$1\" \"$0\"; exit 0";

    private static final Pattern DONE = Pattern.compile("DONE (-?\\d+)");

    private DetachedTurn() {}

    public static String dir(String turnId) {
        return ROOT + "/" + turnId;
    }

    static List<String> launch(String turnId, List<String> argv) {
        var command = new ArrayList<>(List.of("sh", "-c", LAUNCHER, dir(turnId)));
        command.addAll(argv);
        return command;
    }

    static List<String> status(String turnId, int waitSeconds) {
        return List.of("sh", "-c", STATUS, dir(turnId), String.valueOf(Math.max(0, waitSeconds)));
    }

    static List<String> cancel(String turnId, int graceSeconds) {
        return List.of("sh", "-c", CANCEL, dir(turnId), String.valueOf(Math.max(1, graceSeconds)));
    }

    static List<String> prepare(String turnId, int graceSeconds) {
        return List.of("sh", "-c", PREPARE, dir(turnId), String.valueOf(Math.max(1, graceSeconds)));
    }

    static List<String> writePrivate(String path) {
        return List.of("sh", "-c", WRITE_PRIVATE, path);
    }

    static List<String> head(String path, long bytes) {
        return List.of("sh", "-c", HEAD, path, String.valueOf(bytes));
    }

    static List<String> tail(String path, long bytes) {
        return List.of("sh", "-c", TAIL, path, String.valueOf(bytes));
    }

    /** What one observation of a turn found. */
    public sealed interface Status {
        /** The directory has never had a turn started in it. */
        record None() implements Status {}

        record Running() implements Status {}

        /** Finished; {@code exitCode} is the status of the {@code timeout ... claude} command. */
        record Done(int exitCode) implements Status {}

        /** Started, now gone without an exit status: killed with its pod or by the kernel. */
        record Lost() implements Status {}
    }

    /**
     * Parse the output of {@link #STATUS}. Anything else is an error rather than
     * a guess: a status that is misread as "running" waits until the deadline,
     * one misread as "lost" relaunches a live turn.
     */
    static Status parse(String stdout) {
        String line = stdout == null ? "" : stdout.strip();
        return switch (line) {
            case "NONE" -> new Status.None();
            case "RUNNING" -> new Status.Running();
            case "LOST" -> new Status.Lost();
            default -> {
                var m = DONE.matcher(line);
                if (m.matches()) yield new Status.Done(Integer.parseInt(m.group(1)));
                throw new IllegalStateException("Unexpected turn status: '" + line + "'");
            }
        };
    }
}
