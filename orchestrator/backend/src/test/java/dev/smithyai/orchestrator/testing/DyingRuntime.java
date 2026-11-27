package dev.smithyai.orchestrator.testing;

import dev.smithyai.orchestrator.service.docker.ContainerRuntime;
import dev.smithyai.orchestrator.service.docker.dto.ContainerSpec;
import dev.smithyai.orchestrator.service.docker.dto.ExecResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A runtime that plays an orchestrator dying mid-turn: it passes calls through
 * until a detached turn reports {@code LAUNCHED}, then refuses everything, as a
 * killed JVM never returns from its wait.
 */
record DyingRuntime(ContainerRuntime delegate, AtomicBoolean dead) implements ContainerRuntime {
    @Override
    public ExecResult exec(
        String name,
        List<String> command,
        Map<String, String> env,
        String workdir,
        byte[] stdin,
        Duration timeout
    ) {
        if (dead.get()) throw new OrchestratorDied();
        var result = delegate.exec(name, command, env, workdir, stdin, timeout);
        if (result.stdout().strip().equals("LAUNCHED")) dead.set(true);
        return result;
    }

    @Override
    public byte[] execForBytes(String name, List<String> command, Duration timeout) {
        if (dead.get()) throw new OrchestratorDied();
        return delegate.execForBytes(name, command, timeout);
    }

    @Override
    public void create(ContainerSpec spec) {
        delegate.create(spec);
    }

    @Override
    public boolean exists(String name) {
        return delegate.exists(name);
    }

    @Override
    public Optional<Boolean> isRunning(String name) {
        return delegate.isRunning(name);
    }

    @Override
    public boolean start(String name) {
        return delegate.start(name);
    }

    @Override
    public List<String> listManaged(boolean includeStopped) {
        return delegate.listManaged(includeStopped);
    }

    @Override
    public String logs(String name, int tailLines) {
        return delegate.logs(name, tailLines);
    }

    @Override
    public String ownLogs(int tailLines) {
        return delegate.ownLogs(tailLines);
    }

    @Override
    public void remove(String name) {
        delegate.remove(name);
    }

    /** Thrown by every call once the simulated orchestrator is dead. */
    static final class OrchestratorDied extends RuntimeException {

        OrchestratorDied() {
            super("orchestrator died");
        }
    }
}
