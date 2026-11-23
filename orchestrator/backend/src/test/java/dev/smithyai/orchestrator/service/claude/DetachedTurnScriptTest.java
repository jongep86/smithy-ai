package dev.smithyai.orchestrator.service.claude;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The turn scripts, run for real by {@code sh} on this machine.
 *
 * <p>Each script takes its turn directory as {@code $0}, so they can run against
 * a temporary directory here exactly as they run under {@code /tmp/smithy-turns}
 * in a task container. A stub stands in for claude. Needs {@code flock},
 * {@code setsid} and {@code timeout} (util-linux and coreutils), which every
 * Linux CI runner and every task image has; skipped elsewhere.
 */
class DetachedTurnScriptTest {

    @TempDir
    Path tmp;

    @BeforeAll
    static void requireTools() {
        assumeTrue(
            run(List.of("sh", "-c", "command -v flock && command -v setsid && command -v timeout"), null).rc == 0,
            "flock, setsid and timeout are needed"
        );
    }

    @Test
    void aTurnRunsToItsExitCodeAndKeepsItsOutputInFiles() throws Exception {
        Path dir = tmp.resolve("turn-1");
        Path stub = stub("cat > /dev/null; echo working >&2; printf '{\"result\":\"ok\"}'");
        prompt(dir, "hello");

        var launched = run(launch(dir, List.of("timeout", "30s", stub.toString())), null);
        assertEquals(0, launched.rc, launched.err);
        assertEquals("LAUNCHED", launched.out.strip());

        assertEquals(new DetachedTurn.Status.Done(0), status(dir, 10));
        assertEquals("{\"result\":\"ok\"}", Files.readString(dir.resolve("out")));
        assertEquals("working\n", Files.readString(dir.resolve("err")));
        assertFalse(Files.exists(dir.resolve("rc.tmp")), "rc is renamed into place");
    }

    @Test
    void thePromptReachesClaudeAsStdinAndArgumentsArriveVerbatim() throws Exception {
        Path dir = tmp.resolve("turn-quotes");
        // Echo each argument on its own line, NUL-free, then the prompt.
        Path stub = stub("for a in \"$@\"; do printf '[%s]\\n' \"$a\"; done; cat");
        String prompt = "line one\nit's \"quoted\" $HOME `id` \\ end";
        prompt(dir, prompt);
        var args = List.of(
            "timeout",
            "30s",
            stub.toString(),
            "--json-schema",
            "{\"a\": \"b c\"}",
            "it's",
            "two\nlines",
            "$(id)"
        );

        assertEquals(0, run(launch(dir, args), null).rc);
        assertEquals(new DetachedTurn.Status.Done(0), status(dir, 10));

        String out = Files.readString(dir.resolve("out"));
        assertEquals("[--json-schema]\n[{\"a\": \"b c\"}]\n[it's]\n[two\nlines]\n[$(id)]\n" + prompt, out);
        // cmd is for a human: one argument per line, the full argv.
        assertTrue(Files.readString(dir.resolve("cmd")).startsWith("timeout\n30s\n"));
    }

    @Test
    void turnFilesArePrivate() throws Exception {
        Path dir = tmp.resolve("turn-private");
        prompt(dir, "p");
        assertEquals(
            0,
            run(launch(dir, List.of("timeout", "30s", stub("cat >/dev/null; echo {}").toString())), null).rc
        );
        assertEquals(new DetachedTurn.Status.Done(0), status(dir, 10));

        assertEquals(
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
            Files.getPosixFilePermissions(dir)
        );
        for (String f : List.of("prompt", "out", "err", "rc", "lock", "pid")) {
            var perms = Files.getPosixFilePermissions(dir.resolve(f));
            assertFalse(
                perms.contains(PosixFilePermission.OTHERS_READ) || perms.contains(PosixFilePermission.GROUP_READ),
                f + " is " + perms
            );
        }
    }

    @Test
    void aRunningTurnIsRunningAndASecondLauncherStartsNothing() throws Exception {
        Path dir = tmp.resolve("turn-running");
        Path count = tmp.resolve("starts");
        Path stub = stub("echo x >> " + count + "; sleep 3; echo '{}'");
        prompt(dir, "p");
        var argv = List.of("timeout", "30s", stub.toString());

        assertEquals(0, run(launch(dir, argv), null).rc);
        assertEquals(0, run(launch(dir, argv), null).rc, "idempotent per turn id");
        assertEquals(new DetachedTurn.Status.Running(), status(dir, 0));

        assertEquals(new DetachedTurn.Status.Done(0), status(dir, 10));
        assertEquals(1, Files.readAllLines(count).size(), "one process, not two");
    }

    @Test
    void aTurnOutlivesTheProcessThatLaunchedIt() throws Exception {
        Path dir = tmp.resolve("turn-detached");
        Path stub = stub("cat >/dev/null; sleep 2; echo '{\"result\":\"survived\"}'");
        prompt(dir, "p");

        // The exec: the launcher, then a shell that stays (as an exec stream
        // would). Kill that whole process tree once the turn has started: what a
        // dropped stream or a dead orchestrator does to an exec at worst.
        var command = new ArrayList<>(
            List.of("sh", "-c", "sh -c \"$0\" \"$@\"; sleep 30", DetachedTurn.LAUNCHER, dir.toString())
        );
        command.addAll(List.of("timeout", "30s", stub.toString()));
        var p = new ProcessBuilder(command).start();
        for (int i = 0; i < 100 && !Files.exists(dir.resolve("pid")); i++) Thread.sleep(100);
        assertTrue(Files.exists(dir.resolve("pid")), "turn started");
        // Let the launcher itself return (it polls every 100 ms), so what is
        // killed below is the exec's tree, of which the turn is no longer part.
        Thread.sleep(500);
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        assertTrue(p.waitFor(10, TimeUnit.SECONDS));

        assertEquals(new DetachedTurn.Status.Running(), status(dir, 0));
        assertEquals(new DetachedTurn.Status.Done(0), status(dir, 10));
        assertEquals("{\"result\":\"survived\"}\n", Files.readString(dir.resolve("out")));
    }

    @Test
    void theBudgetIsTimeoutsExitCode() throws Exception {
        Path dir = tmp.resolve("turn-timeout");
        prompt(dir, "p");
        assertEquals(
            0,
            run(launch(dir, List.of("timeout", "--kill-after=1s", "1s", stub("sleep 30").toString())), null).rc
        );

        assertEquals(new DetachedTurn.Status.Done(124), status(dir, 15));
    }

    @Test
    void cancelEndsTheTurnWithSigterm() throws Exception {
        Path dir = tmp.resolve("turn-cancel");
        prompt(dir, "p");
        assertEquals(0, run(launch(dir, List.of("timeout", "60s", stub("sleep 60").toString())), null).rc);

        var cancelled = run(List.of("sh", "-c", DetachedTurn.CANCEL, dir.toString(), "5"), null);
        assertEquals(0, cancelled.rc, cancelled.err);
        assertEquals(new DetachedTurn.Status.Done(143), status(dir, 5));
    }

    @Test
    void aTurnKilledWithoutAnExitCodeIsLost() throws Exception {
        Path dir = tmp.resolve("turn-lost");
        prompt(dir, "p");
        assertEquals(0, run(launch(dir, List.of("timeout", "60s", stub("sleep 60").toString())), null).rc);
        String pid = Files.readString(dir.resolve("pid")).strip();
        // The whole session, launcher shell included, as the kernel or a dying pod would.
        String sid = run(List.of("ps", "-o", "sid=", "-p", pid), null).out.strip();
        run(List.of("pkill", "-KILL", "-s", sid), null);

        assertEquals(new DetachedTurn.Status.Lost(), status(dir, 5));
    }

    @Test
    void aDirectoryThatNeverHadATurnIsNone() throws Exception {
        Path dir = tmp.resolve("turn-none");
        prompt(dir, "p");
        assertEquals(new DetachedTurn.Status.None(), status(dir, 0));
        assertEquals(new DetachedTurn.Status.None(), status(tmp.resolve("missing"), 0));
    }

    @Test
    void prepareStopsAndRemovesEveryOtherTurn() throws Exception {
        Path old = tmp.resolve("turns/old");
        prompt(old, "p");
        assertEquals(0, run(launch(old, List.of("timeout", "60s", stub("sleep 60").toString())), null).rc);
        Path finished = tmp.resolve("turns/finished");
        Files.createDirectories(finished);
        Path next = tmp.resolve("turns/next");

        var prepared = run(List.of("sh", "-c", DetachedTurn.PREPARE, next.toString(), "5"), null);
        assertEquals(0, prepared.rc, prepared.err);

        assertFalse(Files.exists(old), "a live turn is stopped and removed");
        assertFalse(Files.exists(finished));
        assertTrue(Files.isDirectory(next));
    }

    @Test
    void headAndTailCapWhatIsRead() throws Exception {
        Path f = tmp.resolve("big");
        Files.writeString(f, "0123456789");
        assertEquals("0123", run(List.of("sh", "-c", DetachedTurn.HEAD, f.toString(), "4"), null).out);
        assertEquals("789", run(List.of("sh", "-c", DetachedTurn.TAIL, f.toString(), "3"), null).out);
        var absent = run(List.of("sh", "-c", DetachedTurn.HEAD, tmp.resolve("nope").toString(), "4"), null);
        assertEquals(0, absent.rc);
        assertEquals("", absent.out);
    }

    @Test
    void writePrivateCreatesAnOwnerOnlyFile() throws Exception {
        Path f = tmp.resolve("secret");
        assertEquals(0, run(List.of("sh", "-c", DetachedTurn.WRITE_PRIVATE, f.toString()), "it's \"x\"\n").rc);
        assertEquals("it's \"x\"\n", Files.readString(f));
        assertEquals(
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(f)
        );
    }

    @Test
    void statusParsingIsStrict() {
        assertEquals(new DetachedTurn.Status.Done(0), DetachedTurn.parse("DONE 0\n"));
        assertEquals(new DetachedTurn.Status.Done(124), DetachedTurn.parse("DONE 124"));
        assertEquals(new DetachedTurn.Status.Running(), DetachedTurn.parse("RUNNING\n"));
        assertEquals(new DetachedTurn.Status.Lost(), DetachedTurn.parse("LOST"));
        assertEquals(new DetachedTurn.Status.None(), DetachedTurn.parse("NONE"));
        assertThrows(IllegalStateException.class, () -> DetachedTurn.parse(""));
        assertThrows(IllegalStateException.class, () -> DetachedTurn.parse("DONE"));
        assertThrows(IllegalStateException.class, () -> DetachedTurn.parse("sh: flock: not found"));
    }

    @Test
    void commandsCarryArgumentsAsPositionalParametersOnly() {
        var command = DetachedTurn.launch("abc", List.of("timeout", "1s", "claude", "'; rm -rf / #"));
        assertEquals(
            List.of(
                "sh",
                "-c",
                DetachedTurn.LAUNCHER,
                "/tmp/smithy-turns/abc",
                "timeout",
                "1s",
                "claude",
                "'; rm -rf / #"
            ),
            command
        );
        assertEquals(
            List.of("sh", "-c", DetachedTurn.STATUS, "/tmp/smithy-turns/abc", "20"),
            DetachedTurn.status("abc", 20)
        );
    }

    // ── Plumbing ─────────────────────────────────────────────

    private Path stub(String body) throws IOException {
        Path f = Files.createTempFile(tmp, "stub", ".sh");
        Files.writeString(f, "#!/bin/sh\n" + body + "\n");
        f.toFile().setExecutable(true);
        return f;
    }

    private static void prompt(Path dir, String prompt) throws IOException {
        Files.createDirectories(dir);
        var r = run(List.of("sh", "-c", DetachedTurn.WRITE_PRIVATE, dir.resolve("prompt").toString()), prompt);
        assertEquals(0, r.rc, r.err);
    }

    private static List<String> launch(Path dir, List<String> argv) {
        var command = new ArrayList<>(List.of("sh", "-c", DetachedTurn.LAUNCHER, dir.toString()));
        command.addAll(argv);
        return command;
    }

    private static DetachedTurn.Status status(Path dir, int wait) {
        var r = run(List.of("sh", "-c", DetachedTurn.STATUS, dir.toString(), String.valueOf(wait)), null);
        assertEquals(0, r.rc, r.err);
        return DetachedTurn.parse(r.out);
    }

    private record Result(int rc, String out, String err) {}

    private static Result run(List<String> command, String stdin) {
        try {
            var p = new ProcessBuilder(command).start();
            if (stdin != null) p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(p.waitFor(60, TimeUnit.SECONDS), "timed out: " + command);
            return new Result(p.exitValue(), out, err);
        } catch (IOException | InterruptedException e) {
            throw new AssertionError(e);
        }
    }
}
