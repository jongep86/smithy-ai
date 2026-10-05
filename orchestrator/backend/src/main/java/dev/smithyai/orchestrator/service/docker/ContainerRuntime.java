package dev.smithyai.orchestrator.service.docker;

import dev.smithyai.orchestrator.service.docker.dto.ContainerSpec;
import dev.smithyai.orchestrator.service.docker.dto.ExecResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where task containers actually run.
 *
 * <p>{@link ContainerService} owns what a task container is — its environment,
 * its init handshake, its state file — and talks to the outside world only
 * through these primitives, so the same lifecycle runs on a Docker host or in a
 * Kubernetes namespace. Names are the ones the orchestrator assigned; a runtime
 * that has to rewrite them for its own rules maps them back.
 */
public interface ContainerRuntime {
    /** Create the container and start it. Returns once it is running, not once init is done. */
    void create(ContainerSpec spec);

    boolean exists(String name);

    /** Whether the container is running; empty when it cannot be inspected at all. */
    Optional<Boolean> isRunning(String name);

    /** Start a container that exists but is not running. Returns false when that failed. */
    boolean start(String name);

    /**
     * Names of containers labelled {@code smithy.managed=true}.
     *
     * @throws RuntimeException when the runtime cannot be queried
     */
    List<String> listManaged(boolean includeStopped);

    String logs(String name, int tailLines);

    /** Logs of the orchestrator's own container, for the dashboard. */
    String ownLogs(int tailLines);

    /**
     * Run a command in the container.
     *
     * @param workdir working directory, or null for the container's default
     * @param stdin   bytes written to the command's stdin before closing it, or null for none
     * @param timeout null for the runtime's default; on expiry the result has exit code 124
     */
    ExecResult exec(
        String name,
        List<String> command,
        Map<String, String> environment,
        String workdir,
        byte[] stdin,
        Duration timeout
    );

    /** Run a command and return its raw stdout; throws when it exits non-zero or times out. */
    byte[] execForBytes(String name, List<String> command, Duration timeout);

    /** Stop and delete the container and anything that belongs only to it. */
    void remove(String name);
}
