package dev.smithyai.orchestrator.config;

import dev.smithyai.orchestrator.service.forgejo.ForgejoClient;
import dev.smithyai.orchestrator.service.gitlab.GitLabClient;
import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VcsConfig {

    @Bean
    @Qualifier("smithyVcs")
    public VcsClient smithyVcsClient(OrchestratorConfig config) {
        return createVcsClient(config, config.resolvedVcsProvider(), false);
    }

    @Bean
    @Qualifier("smithyIssueTracker")
    public IssueTrackerClient smithyIssueTrackerClient(
        OrchestratorConfig config,
        @Qualifier("smithyVcs") VcsClient smithyVcs
    ) {
        String issueProvider = config.resolvedIssueProvider();
        String vcsProvider = config.resolvedVcsProvider();
        // If same provider, reuse the VcsClient instance (it implements both)
        if (issueProvider.equals(vcsProvider) && smithyVcs instanceof IssueTrackerClient itc) {
            return itc;
        }
        return createIssueTrackerClient(config, issueProvider, false);
    }

    @Bean
    @Qualifier("architectVcs")
    public VcsClient architectVcsClient(OrchestratorConfig config, @Qualifier("smithyVcs") VcsClient smithyVcs) {
        if (!config.hasArchitect()) {
            // No architect token — fall back to smithy client
            return smithyVcs;
        }
        return createVcsClient(config, config.resolvedVcsProvider(), true);
    }

    @Bean
    @Qualifier("architectIssueTracker")
    public IssueTrackerClient architectIssueTrackerClient(
        OrchestratorConfig config,
        @Qualifier("architectVcs") VcsClient architectVcs,
        @Qualifier("smithyIssueTracker") IssueTrackerClient smithyIssueTracker
    ) {
        if (!config.hasArchitect()) {
            return smithyIssueTracker;
        }
        String issueProvider = config.resolvedIssueProvider();
        String vcsProvider = config.resolvedVcsProvider();
        if (issueProvider.equals(vcsProvider) && architectVcs instanceof IssueTrackerClient itc) {
            return itc;
        }
        return createIssueTrackerClient(config, issueProvider, true);
    }

    private VcsClient createVcsClient(OrchestratorConfig config, String provider, boolean architect) {
        return switch (provider) {
            case "gitlab" -> {
                String token = architect ? config.architectGitlabToken() : config.smithyGitlabToken();
                yield new GitLabClient(config.gitlabUrl(), config.gitlabExternalUrl(), token);
            }
            default -> {
                String token = architect ? config.architectForgejoToken() : config.smithyForgejoToken();
                yield new ForgejoClient(config.forgejoUrl(), token);
            }
        };
    }

    private IssueTrackerClient createIssueTrackerClient(OrchestratorConfig config, String provider, boolean architect) {
        return switch (provider) {
            case "gitlab" -> {
                String token = architect ? config.architectGitlabToken() : config.smithyGitlabToken();
                yield new GitLabClient(config.gitlabUrl(), config.gitlabExternalUrl(), token);
            }
            default -> {
                String token = architect ? config.architectForgejoToken() : config.smithyForgejoToken();
                yield new ForgejoClient(config.forgejoUrl(), token);
            }
        };
    }
}
