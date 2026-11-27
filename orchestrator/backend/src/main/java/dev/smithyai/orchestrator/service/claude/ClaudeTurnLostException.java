package dev.smithyai.orchestrator.service.claude;

import lombok.Getter;

/**
 * A detached turn whose processes disappeared without an exit status, more often
 * than {@code agent.claude.lostTurnRetries} allows.
 *
 * <p>This is what a pod that was recreated or a process the kernel killed looks
 * like from outside: the lock is free and there is no {@code rc}. Raised instead
 * of waiting for a result that cannot come. Extends {@link IllegalStateException}
 * so the run engine reports it like any other failed turn.
 */
@Getter
public class ClaudeTurnLostException extends IllegalStateException {

    private final String containerName;
    private final String turnDirectory;
    private final int attempts;

    /** The last part of the turn's stderr, often the only clue why it died. */
    private final String errorTail;

    public ClaudeTurnLostException(String containerName, String turnDirectory, int attempts, String errorTail) {
        super(
            (
                "Claude turn on %s was lost %d time(s): its process ended without an exit status " +
                "(pod recreated or process killed). Turn directory: %s.%s"
            ).formatted(
                containerName,
                attempts,
                turnDirectory,
                errorTail == null || errorTail.isBlank() ? "" : " Last stderr:\n" + errorTail
            )
        );
        this.containerName = containerName;
        this.turnDirectory = turnDirectory;
        this.attempts = attempts;
        this.errorTail = errorTail == null ? "" : errorTail;
    }
}
