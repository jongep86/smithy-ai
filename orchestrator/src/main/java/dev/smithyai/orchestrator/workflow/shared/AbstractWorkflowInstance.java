package dev.smithyai.orchestrator.workflow.shared;

import dev.smithyai.orchestrator.config.OrchestratorConfig;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.service.claude.ClaudeSession;
import dev.smithyai.orchestrator.service.claude.PromptRenderer;
import dev.smithyai.orchestrator.service.docker.ContainerSession;
import dev.smithyai.orchestrator.service.forgejo.ForgejoClient;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AbstractWorkflowInstance {

    protected final ContainerSession session;
    protected ClaudeSession claude;
    protected final ForgejoClient forgejoClient;
    protected final PromptRenderer renderer;
    protected final OrchestratorConfig config;
    private final Runnable destroyCallback;
    private final ExecutorService eventThread;

    protected AbstractWorkflowInstance(
        ContainerSession session,
        ForgejoClient forgejoClient,
        PromptRenderer renderer,
        OrchestratorConfig config,
        List<String> tools,
        Runnable destroyCallback
    ) {
        this(session, forgejoClient, renderer, config, tools, destroyCallback, null);
    }

    protected AbstractWorkflowInstance(
        ContainerSession session,
        ForgejoClient forgejoClient,
        PromptRenderer renderer,
        OrchestratorConfig config,
        List<String> tools,
        Runnable destroyCallback,
        String existingSessionId
    ) {
        this.session = session;
        this.claude =
            existingSessionId != null
                ? new ClaudeSession(session, tools, existingSessionId)
                : new ClaudeSession(session, tools);
        this.forgejoClient = forgejoClient;
        this.renderer = renderer;
        this.config = config;
        this.destroyCallback = destroyCallback;
        this.eventThread = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("wf-" + session.getContainerName() + "-", 0).factory()
        );
    }

    public String containerName() {
        return session.getContainerName();
    }

    public ContainerSession session() {
        return session;
    }

    public boolean exists() {
        return session.exists();
    }

    public void onEvent(WorkflowEvent event) {
        eventThread.submit(() -> {
            try {
                handleEvent(event);
            } catch (Exception e) {
                log.error("Event {} failed in {}", event.getClass().getSimpleName(), session.getContainerName(), e);
            }
        });
    }

    protected abstract void handleEvent(WorkflowEvent event);

    public void destroy() {
        destroyCallback.run();
        eventThread.shutdown();
        try {
            if (!eventThread.awaitTermination(30, TimeUnit.SECONDS)) {
                eventThread.shutdownNow();
            }
        } catch (InterruptedException e) {
            eventThread.shutdownNow();
            Thread.currentThread().interrupt();
        }
        session.destroy();
    }

    protected void syncSessionId() {
        session.updateState(s -> s.withSessionId(claude.getSessionId()));
    }
}
