package dev.smithyai.orchestrator.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "orchestrator")
public record OrchestratorConfig(
    // Forgejo settings
    String forgejoUrl,
    String forgejoExternalUrl,
    String smithyForgejoToken,
    String architectForgejoToken,
    // GitLab settings
    String gitlabUrl,
    String gitlabExternalUrl,
    String smithyGitlabToken,
    String architectGitlabToken,
    String gitlabWebhookSecret,
    // Provider selection
    String vcsProvider,
    String issueProvider,
    // Common settings
    String claudeCodeOauthToken,
    String dockerNetwork,
    String taskImage,
    String webhookSecret,
    String cacheVolumes,
    String architectBotUser,
    String dockerCommand,
    String smithyBotUser
) {
    private static final Map<String, CacheVolumeEntry> CACHE_VOLUME_MAP = Map.of(
        "pnpm",
        new CacheVolumeEntry("cache-pnpm", "/root/.local/share/pnpm/store"),
        "npm",
        new CacheVolumeEntry("cache-npm", "/root/.npm"),
        "maven",
        new CacheVolumeEntry("cache-maven", "/root/.m2/repository"),
        "gradle",
        new CacheVolumeEntry("cache-gradle", "/root/.gradle/caches")
    );

    public Map<String, String> getCacheVolumeMap() {
        var result = new LinkedHashMap<String, String>();
        if (cacheVolumes == null || cacheVolumes.isBlank()) return result;
        for (String name : cacheVolumes.split(",")) {
            name = name.strip();
            var entry = CACHE_VOLUME_MAP.get(name);
            if (entry != null) {
                result.put(entry.volumeName(), entry.mountPath());
            }
        }
        return result;
    }

    public boolean hasArchitect() {
        return hasArchitectToken();
    }

    public String resolvedVcsProvider() {
        return vcsProvider != null && !vcsProvider.isBlank() ? vcsProvider : "forgejo";
    }

    public String resolvedIssueProvider() {
        return issueProvider != null && !issueProvider.isBlank() ? issueProvider : resolvedVcsProvider();
    }

    public String resolvedSmithyBotUser() {
        return smithyBotUser != null && !smithyBotUser.isBlank() ? smithyBotUser : "smithy";
    }

    public String resolvedExternalUrl() {
        return switch (resolvedVcsProvider()) {
            case "gitlab" -> gitlabExternalUrl != null && !gitlabExternalUrl.isBlank() ? gitlabExternalUrl : gitlabUrl;
            default -> forgejoExternalUrl;
        };
    }

    private boolean hasArchitectToken() {
        return switch (resolvedVcsProvider()) {
            case "gitlab" -> architectGitlabToken != null && !architectGitlabToken.isBlank();
            default -> architectForgejoToken != null && !architectForgejoToken.isBlank();
        };
    }

    private record CacheVolumeEntry(String volumeName, String mountPath) {}
}
