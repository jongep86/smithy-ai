package dev.smithyai.orchestrator.service.docker.dto;

import java.time.Instant;

/**
 * The detached Claude turn a container is running or last ran, as recorded in
 * its run state. It lives in the state file because the state file sits on the
 * same volume as the turn's own files, so the two survive or vanish together.
 *
 * @param id         directory name under {@code /tmp/smithy-turns}
 * @param promptHash identity of the request (prompt, schema, permission mode, model)
 * @param sessionId  the Claude session the turn runs in
 * @param resume     whether it was started with {@code --resume}
 * @param startedAt  when the first attempt was launched (orchestrator clock)
 * @param deadline   when the budget of the first attempt runs out; relaunches get what is left
 * @param phase      {@code LAUNCHED} until the result was read, then {@code COLLECTED}
 * @param attempts   relaunches after a loss so far, 0 for the first attempt
 */
public record ActiveTurn(
    String id,
    String promptHash,
    String sessionId,
    boolean resume,
    Instant startedAt,
    Instant deadline,
    Phase phase,
    int attempts
) {
    public enum Phase {
        LAUNCHED,
        COLLECTED,
    }

    public ActiveTurn collected() {
        return new ActiveTurn(id, promptHash, sessionId, resume, startedAt, deadline, Phase.COLLECTED, attempts);
    }

    /** The next attempt after a loss: a new directory, same identity and deadline. */
    public ActiveTurn relaunched(String newId, boolean resumeSession) {
        return new ActiveTurn(
            newId,
            promptHash,
            sessionId,
            resumeSession,
            startedAt,
            deadline,
            Phase.LAUNCHED,
            attempts + 1
        );
    }

    public boolean isLaunched() {
        return phase == Phase.LAUNCHED;
    }
}
