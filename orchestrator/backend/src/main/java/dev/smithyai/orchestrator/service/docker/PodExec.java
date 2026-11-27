package dev.smithyai.orchestrator.service.docker;

import dev.smithyai.orchestrator.service.docker.dto.ExecResult;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;

/**
 * Commands and logs of a task pod, whichever object owns that pod.
 *
 * <p>The StatefulSet and the Sandbox runtimes differ in what they create and
 * watch; once a pod runs, both reach it the same way, through {@code pods/exec}
 * and {@code pods/log}. That part lives here so it exists once.
 */
@Slf4j
final class PodExec {

    static final String CONTAINER = "task";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STREAM_DRAIN = Duration.ofSeconds(5);
    private static final Duration STDIN_UPLOAD_TIMEOUT = Duration.ofSeconds(60);
    private static final int UPLOAD_CHUNK = 48 * 1024;

    /** Runs "$@" with stdin from the file in $0, removes the file, keeps the exit code. */
    private static final String STDIN_FROM_FILE = "f=\"$0\"; \"$@\" < \"$f\"; rc=$?; rm -f \"$f\"; exit $rc";

    private final KubernetesClient client;
    private final String namespace;

    PodExec(KubernetesClient client, String namespace) {
        this.client = client;
        this.namespace = namespace;
    }

    ExecResult exec(
        String pod,
        List<String> command,
        Map<String, String> environment,
        String workdir,
        byte[] stdin,
        Duration timeout
    ) {
        var wrapped = wrap(command, environment, workdir);
        RawResult raw;
        if (stdin == null) {
            raw = run(pod, wrapped, timeout);
        } else {
            // The exec stdin channel is not usable here: with this client a
            // command that writes to stdout after reading stdin hangs, and
            // payloads above ~16 KiB are sometimes lost whole (kubectl handles
            // both). So stdin is shipped to a file as arguments, and the
            // command reads that file.
            String file = "/tmp/.smithy-stdin-" + UUID.randomUUID();
            raw = upload(pod, file, stdin);
            if (raw.exitCode == 0) {
                var fromFile = new ArrayList<String>(List.of("sh", "-c", STDIN_FROM_FILE, file));
                fromFile.addAll(wrapped);
                raw = run(pod, fromFile, timeout);
            }
        }
        return new ExecResult(
            raw.exitCode,
            new String(raw.stdout, StandardCharsets.UTF_8),
            new String(raw.stderr, StandardCharsets.UTF_8)
        );
    }

    byte[] execForBytes(String pod, List<String> command, Duration timeout) {
        var raw = run(pod, command, timeout);
        if (raw.exitCode != 0) {
            throw new RuntimeException(
                "exec failed (exit " +
                    raw.exitCode +
                    ") in " +
                    pod +
                    ": " +
                    command +
                    "\nstderr: " +
                    new String(raw.stderr, StandardCharsets.UTF_8)
            );
        }
        return raw.stdout;
    }

    String logs(String pod, int tailLines) {
        try {
            return client
                .pods()
                .inNamespace(namespace)
                .withName(pod)
                .inContainer(CONTAINER)
                .tailingLines(tailLines)
                .getLog();
        } catch (KubernetesClientException e) {
            return "Failed to read logs of " + pod + ": " + e.getMessage();
        }
    }

    /** Logs of the orchestrator's own pod, for the dashboard. */
    String ownLogs(int tailLines) {
        String self = System.getenv("HOSTNAME");
        if (self == null || self.isBlank()) {
            return "Unable to determine own pod name (HOSTNAME not set)";
        }
        String ownNamespace = System.getenv().getOrDefault("POD_NAMESPACE", namespace);
        try {
            var pod = client.pods().inNamespace(ownNamespace).withName(self);
            Pod current = pod.get();
            if (current == null) {
                return "Own pod " + ownNamespace + "/" + self + " not found";
            }
            var containers = current.getSpec().getContainers();
            String container =
                containers.size() == 1
                    ? containers.getFirst().getName()
                    : System.getenv().getOrDefault("SMITHY_CONTAINER_NAME", "orchestrator");
            return pod.inContainer(container).tailingLines(tailLines).getLog();
        } catch (KubernetesClientException e) {
            return "Failed to read own logs: " + e.getMessage();
        }
    }

    /**
     * Kubernetes exec has no working-directory or environment flags. Both go in
     * as positional arguments to a fixed script, so nothing a caller passes is
     * ever interpolated into shell source.
     */
    static List<String> wrap(List<String> command, Map<String, String> environment, String workdir) {
        var env = new ArrayList<String>();
        if (environment != null) {
            environment.forEach((k, v) -> env.add(k + "=" + v));
        }
        var wrapped = new ArrayList<String>();
        if (workdir != null) {
            wrapped.addAll(List.of("sh", "-c", "cd \"$0\" && exec \"$@\"", workdir));
        }
        if (!env.isEmpty()) {
            wrapped.add("env");
            wrapped.addAll(env);
        }
        wrapped.addAll(command);
        return wrapped;
    }

    private record RawResult(int exitCode, byte[] stdout, byte[] stderr) {}

    /**
     * Write {@code data} to {@code file} in base64 chunks passed as arguments,
     * then check the size. A chunk stays well under Linux's 128 KiB limit on a
     * single argument.
     */
    private RawResult upload(String pod, String file, byte[] data) {
        var encoder = Base64.getEncoder();
        int offset = 0;
        boolean first = true;
        do {
            int length = Math.min(UPLOAD_CHUNK, data.length - offset);
            String chunk = encoder.encodeToString(Arrays.copyOfRange(data, offset, offset + length));
            String redirect = first ? ">" : ">>";
            var result = run(
                pod,
                List.of("sh", "-c", "printf %s \"$1\" | base64 -d " + redirect + " \"$0\"", file, chunk),
                STDIN_UPLOAD_TIMEOUT
            );
            if (result.exitCode != 0) return result;
            offset += length;
            first = false;
        } while (offset < data.length);

        var size = run(pod, List.of("sh", "-c", "wc -c < \"$0\"", file), STDIN_UPLOAD_TIMEOUT);
        String written = new String(size.stdout, StandardCharsets.UTF_8).strip();
        if (size.exitCode != 0 || !written.equals(String.valueOf(data.length))) {
            return new RawResult(
                1,
                new byte[0],
                ("stdin upload to " + file + " wrote " + written + " of " + data.length + " bytes").getBytes(
                    StandardCharsets.UTF_8
                )
            );
        }
        return size;
    }

    private RawResult run(String pod, List<String> command, Duration timeout) {
        Duration effective = timeout != null ? timeout : DEFAULT_TIMEOUT;
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var closed = new CountDownLatch(1);

        var container = client
            .pods()
            .inNamespace(namespace)
            .withName(pod)
            .inContainer(CONTAINER)
            .writingOutput(out)
            .writingError(err)
            .usingListener(
                new ExecListener() {
                    @Override
                    public void onClose(int code, String reason) {
                        closed.countDown();
                    }

                    @Override
                    public void onFailure(Throwable t, Response failureResponse) {
                        closed.countDown();
                    }
                }
            );

        ExecWatch watch = null;
        try {
            watch = container.exec(command.toArray(String[]::new));
            Integer code = watch.exitCode().get(effective.toMillis(), TimeUnit.MILLISECONDS);
            // The status frame can arrive before the last output frames.
            closed.await(STREAM_DRAIN.toMillis(), TimeUnit.MILLISECONDS);
            return new RawResult(code == null ? 1 : code, out.toByteArray(), err.toByteArray());
        } catch (TimeoutException e) {
            return new RawResult(
                124,
                out.toByteArray(),
                ("Timed out after " + effective).getBytes(StandardCharsets.UTF_8)
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RawResult(130, out.toByteArray(), "Interrupted".getBytes(StandardCharsets.UTF_8));
        } catch (ExecutionException | KubernetesClientException e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            return new RawResult(1, out.toByteArray(), describe(cause).getBytes(StandardCharsets.UTF_8));
        } finally {
            if (watch != null) watch.close();
        }
    }

    /**
     * The exception and what caused it, each named once. fabric8 wraps a refused
     * WebSocket upgrade in a generic "An error has occurred"; the HTTP status
     * and the API server's reason sit in the causes.
     */
    static String describe(Throwable t) {
        var parts = new ArrayList<String>();
        for (Throwable c = t; c != null && parts.size() < 5; c = c.getCause() == c ? null : c.getCause()) {
            String part = c.getClass().getSimpleName() + ": " + c.getMessage();
            if (!parts.contains(part)) parts.add(part);
        }
        return String.join("; caused by ", parts);
    }
}
