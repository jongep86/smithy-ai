package dev.smithyai.orchestrator.workflow.flows.architect;

import dev.smithyai.orchestrator.config.OrchestratorConfig;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.service.claude.PromptRenderer;
import dev.smithyai.orchestrator.service.docker.ContainerService;
import dev.smithyai.orchestrator.service.docker.dto.ContainerState;
import dev.smithyai.orchestrator.service.docker.dto.WorkflowType;
import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.workflow.EventAction;
import dev.smithyai.orchestrator.workflow.shared.AbstractWorkflowFactory;
import dev.smithyai.orchestrator.workflow.shared.utils.Naming;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ArchitectLearnFactory extends AbstractWorkflowFactory<ArchitectLearnInstance> {

    public static final List<String> TOOLS = List.of("Read", "Glob", "Grep", "Edit", "Write", "Bash");

    private final ContainerService containerService;
    private final OrchestratorConfig config;
    private final PromptRenderer renderer;
    private final VcsClient vcsClient;
    private final IssueTrackerClient issueTracker;

    public ArchitectLearnFactory(
        OrchestratorConfig config,
        ContainerService containerService,
        PromptRenderer renderer,
        @Qualifier("architectVcs") VcsClient vcsClient,
        @Qualifier("architectIssueTracker") IssueTrackerClient issueTracker
    ) {
        this.config = config;
        this.containerService = containerService;
        this.renderer = renderer;
        this.vcsClient = vcsClient;
        this.issueTracker = issueTracker;
    }

    @Override
    public EventAction decideEventAction(WorkflowEvent event) {
        return switch (event) {
            case WorkflowEvent.PrMerged e -> {
                var prc = e.prc();
                String contextRepo = Naming.contextRepoName(prc.info().repo());
                if (!vcsClient.repoExists(prc.info().owner(), contextRepo)) {
                    log.warn("Context repo {}/{} does not exist, skipping learning", prc.info().owner(), contextRepo);
                    yield EventAction.IGNORE;
                }
                String key = ArchitectReviewFactory.architectContainerName(
                    prc.info().owner(),
                    prc.info().repo(),
                    "learn-" + prc.number()
                );
                yield new EventAction.Create(key);
            }
            case WorkflowEvent.PrClosed e -> {
                if (!e.info().repo().endsWith("-context")) yield EventAction.IGNORE;
                Integer sourcePrId = Naming.parseIssueIdFromBranch(e.headBranch());
                if (sourcePrId == null) yield EventAction.IGNORE;
                String sourceRepo = e.info().repo().substring(0, e.info().repo().length() - 8);
                String key = ArchitectReviewFactory.architectContainerName(
                    e.info().owner(),
                    sourceRepo,
                    "learn-" + sourcePrId
                );
                yield new EventAction.Destroy(key);
            }
            case WorkflowEvent.PrConversationComment e -> {
                var key = ArchitectReviewFactory.resolveCommentKey(e, "learn-");
                var instance = instances.get(key);
                yield (instance != null && instance.exists()) ? new EventAction.Dispatch(key) : EventAction.IGNORE;
            }
            default -> EventAction.IGNORE;
        };
    }

    @Override
    protected ArchitectLearnInstance createInstance(String key, WorkflowEvent event) {
        var session = containerService.createSession(key);
        return new ArchitectLearnInstance(session, vcsClient, issueTracker, renderer, config, TOOLS, () ->
            removeInstance(key)
        );
    }

    @Override
    public boolean canRecover(String containerName, ContainerState state) {
        return (
            containerName.startsWith("architect.") &&
            containerName.contains(".learn-") &&
            state.workflowType() == WorkflowType.ARCHITECT &&
            !LearnStage.DONE.value().equals(state.stage())
        );
    }

    @Override
    public ArchitectLearnInstance recoverInstance(String containerName, ContainerState state) {
        LearnStage stage = LearnStage.fromValue(state.stage());
        var session = containerService.createSession(containerName);
        return new ArchitectLearnInstance(
            session,
            vcsClient,
            issueTracker,
            renderer,
            config,
            TOOLS,
            () -> removeInstance(containerName),
            stage,
            state.sessionId()
        );
    }
}
