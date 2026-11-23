package dev.smithyai.orchestrator.service.docker.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

public record ContainerState(
    String sessionId,
    String stage,
    String workflow,
    int ciRetryCount,
    boolean ciPaused,
    Instant lastProcessedAt,
    List<String> extraDirs,
    // Left out of the file when absent, so with detached turns off the state
    // file is byte-for-byte what it was.
    @JsonInclude(JsonInclude.Include.NON_NULL) ActiveTurn activeTurn
) {
    public ContainerState {
        // State written before this field existed has no extraDirs.
        if (extraDirs == null) extraDirs = List.of();
        // State written before detached turns, or with them switched off, has
        // no activeTurn: that reads as "no turn in flight".
    }

    /** The shape before detached turns, kept so existing callers need not know about them. */
    public ContainerState(
        String sessionId,
        String stage,
        String workflow,
        int ciRetryCount,
        boolean ciPaused,
        Instant lastProcessedAt,
        List<String> extraDirs
    ) {
        this(sessionId, stage, workflow, ciRetryCount, ciPaused, lastProcessedAt, extraDirs, null);
    }

    public static ContainerState init(String workflow, String stage) {
        return new ContainerState(null, stage, workflow, 0, false, Instant.now(), List.of());
    }

    public ContainerState withSessionId(String sessionId) {
        return new ContainerState(
            sessionId,
            stage,
            workflow,
            ciRetryCount,
            ciPaused,
            lastProcessedAt,
            extraDirs,
            activeTurn
        );
    }

    public ContainerState withStage(String stage) {
        return new ContainerState(
            sessionId,
            stage,
            workflow,
            ciRetryCount,
            ciPaused,
            lastProcessedAt,
            extraDirs,
            activeTurn
        );
    }

    public ContainerState withCiRetryCount(int count) {
        return new ContainerState(sessionId, stage, workflow, count, ciPaused, lastProcessedAt, extraDirs, activeTurn);
    }

    public ContainerState withCiPaused(boolean paused) {
        return new ContainerState(
            sessionId,
            stage,
            workflow,
            ciRetryCount,
            paused,
            lastProcessedAt,
            extraDirs,
            activeTurn
        );
    }

    public ContainerState withExtraDirs(List<String> extraDirs) {
        return new ContainerState(
            sessionId,
            stage,
            workflow,
            ciRetryCount,
            ciPaused,
            lastProcessedAt,
            extraDirs,
            activeTurn
        );
    }

    public ContainerState withActiveTurn(ActiveTurn activeTurn) {
        return new ContainerState(
            sessionId,
            stage,
            workflow,
            ciRetryCount,
            ciPaused,
            lastProcessedAt,
            extraDirs,
            activeTurn
        );
    }

    public ContainerState incrementCiRetryCount() {
        return new ContainerState(
            sessionId,
            stage,
            workflow,
            ciRetryCount + 1,
            ciPaused,
            lastProcessedAt,
            extraDirs,
            activeTurn
        );
    }

    public ContainerState resetCi() {
        return new ContainerState(sessionId, stage, workflow, 0, false, lastProcessedAt, extraDirs, activeTurn);
    }

    public ContainerState touch() {
        return new ContainerState(
            sessionId,
            stage,
            workflow,
            ciRetryCount,
            ciPaused,
            Instant.now(),
            extraDirs,
            activeTurn
        );
    }
}
