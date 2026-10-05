package dev.smithyai.orchestrator.service.docker;

import dev.smithyai.orchestrator.service.docker.dto.ContainerSpec;
import dev.smithyai.orchestrator.service.docker.dto.ExecResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/** Task containers as sibling containers on the Docker host, through the docker CLI. */
@Slf4j
public class DockerRuntime implements ContainerRuntime {

    private final DockerCli docker;
    private final String network;

    public DockerRuntime(DockerCli docker, String network) {
        this.docker = docker;
        this.network = network;
    }

    @Override
    public void create(ContainerSpec spec) {
        var args = new ArrayList<String>();
        args.add("create");
        args.add("--name");
        args.add(spec.name());
        args.add("--network");
        args.add(network);
        args.add("--restart");
        args.add("unless-stopped");

        spec
            .labels()
            .forEach((k, v) -> {
                args.add("--label");
                args.add(k + "=" + v);
            });
        spec
            .caches()
            .forEach((vol, path) -> {
                args.add("-v");
                args.add(vol + ":" + path);
            });
        spec
            .environment()
            .forEach((k, v) -> {
                args.add("-e");
                args.add(k + "=" + v);
            });

        args.add(spec.image());
        args.addAll(spec.command());

        var createResult = docker.run(args);
        if (createResult.exitCode() != 0) {
            throw new RuntimeException("Failed to create container " + spec.name() + ": " + createResult.stderr());
        }

        var startResult = docker.run(List.of("start", spec.name()));
        if (startResult.exitCode() != 0) {
            throw new RuntimeException("Failed to start container " + spec.name() + ": " + startResult.stderr());
        }
    }

    @Override
    public boolean exists(String name) {
        return docker.run(List.of("inspect", name)).exitCode() == 0;
    }

    @Override
    public Optional<Boolean> isRunning(String name) {
        var result = docker.run(List.of("inspect", "--format", "{{.State.Running}}", name));
        if (result.exitCode() != 0) {
            log.warn("Failed to inspect container {}: {}", name, result.stderr());
            return Optional.empty();
        }
        return Optional.of("true".equals(result.stdout().strip()));
    }

    @Override
    public boolean start(String name) {
        var result = docker.run(List.of("start", name));
        if (result.exitCode() != 0) {
            log.warn("Failed to start container {}: {}", name, result.stderr());
            return false;
        }
        return true;
    }

    @Override
    public List<String> listManaged(boolean includeStopped) {
        var args = new ArrayList<String>();
        args.add("ps");
        if (includeStopped) args.add("-a");
        args.addAll(List.of("--filter", "label=smithy.managed=true", "--format", "{{.Names}}"));
        var result = docker.run(args);
        if (result.exitCode() != 0) {
            throw new RuntimeException("docker ps failed: " + result.stderr());
        }
        return result
            .stdout()
            .lines()
            .map(String::strip)
            .filter(s -> !s.isBlank())
            .toList();
    }

    @Override
    public String logs(String name, int tailLines) {
        var result = docker.run(List.of("logs", "--tail", String.valueOf(tailLines), name));
        String logs = result.stdout();
        if (result.stderr() != null && !result.stderr().isBlank()) {
            logs += "\n" + result.stderr();
        }
        return logs;
    }

    @Override
    public String ownLogs(int tailLines) {
        String selfId = System.getenv("HOSTNAME");
        if (selfId == null || selfId.isBlank()) {
            return "Unable to determine own container id (HOSTNAME not set)";
        }
        return logs(selfId, tailLines);
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
        var args = new ArrayList<String>();
        args.add("exec");
        if (stdin != null) {
            args.add("-i");
        }
        if (workdir != null) {
            args.add("-w");
            args.add(workdir);
        }
        if (environment != null) {
            environment.forEach((k, v) -> {
                args.add("-e");
                args.add(k + "=" + v);
            });
        }
        args.add(name);
        args.addAll(command);

        return docker.run(args, stdin, timeout);
    }

    @Override
    public byte[] execForBytes(String name, List<String> command, Duration timeout) {
        var args = new ArrayList<String>();
        args.add("exec");
        args.add(name);
        args.addAll(command);
        return docker.runForBytes(args, timeout);
    }

    @Override
    public void remove(String name) {
        docker.run(List.of("stop", name));
        docker.run(List.of("rm", "-f", name));
    }
}
