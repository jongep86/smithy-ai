package dev.smithyai.orchestrator.service.docker.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A task container as the orchestrator wants it, independent of where it runs.
 *
 * <p>Maps keep their insertion order: the Docker runtime turns them into
 * arguments, and a stable order keeps those reproducible.
 *
 * @param caches cache volume name to mount path, e.g. {@code cache-npm -> /root/.npm}
 */
public record ContainerSpec(
    String name,
    String image,
    List<String> command,
    Map<String, String> labels,
    Map<String, String> caches,
    Map<String, String> environment
) {
    public ContainerSpec {
        command = List.copyOf(command);
        labels = ordered(labels);
        caches = ordered(caches);
        environment = ordered(environment);
    }

    private static Map<String, String> ordered(Map<String, String> map) {
        return map == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
