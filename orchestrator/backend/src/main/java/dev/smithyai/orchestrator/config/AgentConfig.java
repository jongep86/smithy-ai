package dev.smithyai.orchestrator.config;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public record AgentConfig(ClaudeAgentConfig claude) {
    private static final List<String> DEFAULT_TAKEOVER_TOOLS = List.of(
        "Read",
        "Glob",
        "Grep",
        "Bash",
        "Edit",
        "Write",
        "WebFetch"
    );

    public record ClaudeAgentConfig(
        String model,
        SecretRef oauthToken,
        SecretRef apiKey,
        String turnTimeout,
        String takeoverTimeout,
        List<String> takeoverTools,
        Boolean detached,
        String pollInterval,
        Integer lostTurnRetries
    ) {
        private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(20);
        private static final int DEFAULT_LOST_TURN_RETRIES = 1;

        /** The shape before detached turns. */
        public ClaudeAgentConfig(
            String model,
            SecretRef oauthToken,
            SecretRef apiKey,
            String turnTimeout,
            String takeoverTimeout,
            List<String> takeoverTools
        ) {
            this(model, oauthToken, apiKey, turnTimeout, takeoverTimeout, takeoverTools, null, null, null);
        }

        /** Whether turns run detached from their exec stream. Off unless set. */
        public boolean resolvedDetached() {
            return Boolean.TRUE.equals(detached);
        }

        /** Longest single observation of a running detached turn; default 20s. */
        public Duration resolvedPollInterval() {
            return parse(pollInterval, "pollInterval").orElse(DEFAULT_POLL_INTERVAL);
        }

        /** Relaunches of a lost detached turn before it fails; default 1. */
        public int resolvedLostTurnRetries() {
            if (lostTurnRetries == null) return DEFAULT_LOST_TURN_RETRIES;
            if (lostTurnRetries < 0) {
                throw new IllegalStateException(
                    "agent.claude.lostTurnRetries must not be negative, got " + lostTurnRetries
                );
            }
            return lostTurnRetries;
        }

        /**
         * Tools a human-driven turn may use.
         *
         * <p>Not the stage's list: a definition scopes tools per step to constrain
         * the agent when it is working on its own — read-only while planning, write
         * while building — and that does not map onto whatever a person decides to
         * ask for after taking control. So this is its own setting, and it defaults
         * to the full working set rather than to nothing.
         */
        public List<String> resolvedTakeoverTools() {
            return takeoverTools == null || takeoverTools.isEmpty() ? DEFAULT_TAKEOVER_TOOLS : takeoverTools;
        }

        /**
         * Wall-clock budget for one agent turn, or empty to keep the built-in
         * default. Accepts {@code 45m}, {@code 2h}, {@code 900s} or ISO-8601
         * ({@code PT45M}).
         */
        public Optional<Duration> resolvedTurnTimeout() {
            return parse(turnTimeout, "turnTimeout");
        }

        /**
         * Budget for a turn a human drove from the dashboard. Separate because a
         * person is waiting on the reply in a browser, so it wants to be minutes
         * rather than the tens of minutes a build turn is allowed.
         */
        public Optional<Duration> resolvedTakeoverTimeout() {
            return parse(takeoverTimeout, "takeoverTimeout");
        }

        private static Optional<Duration> parse(String raw, String key) {
            if (raw == null || raw.isBlank()) return Optional.empty();
            return Optional.of(parseTurnTimeout(raw, key));
        }

        private static Duration parseTurnTimeout(String raw, String key) {
            Duration parsed;
            try {
                String value = raw.strip();
                if (value.regionMatches(true, 0, "P", 0, 1)) {
                    parsed = Duration.parse(value);
                } else {
                    char unit = value.charAt(value.length() - 1);
                    long amount = Long.parseLong(value.substring(0, value.length() - 1).strip());
                    parsed = switch (Character.toLowerCase(unit)) {
                        case 's' -> Duration.ofSeconds(amount);
                        case 'm' -> Duration.ofMinutes(amount);
                        case 'h' -> Duration.ofHours(amount);
                        default -> throw new IllegalArgumentException("unknown unit '" + unit + "'");
                    };
                }
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                    "agent.claude.%s is not a duration: '%s' (expected e.g. 45m, 2h, 900s or PT45M)".formatted(
                        key,
                        raw
                    ),
                    e
                );
            }
            if (parsed.isZero() || parsed.isNegative()) {
                throw new IllegalStateException("agent.claude.%s must be positive, got '%s'".formatted(key, raw));
            }
            return parsed;
        }
    }
}
