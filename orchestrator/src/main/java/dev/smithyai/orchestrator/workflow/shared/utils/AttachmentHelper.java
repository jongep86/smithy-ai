package dev.smithyai.orchestrator.workflow.shared.utils;

import dev.smithyai.forgejoclient.model.Attachment;
import dev.smithyai.orchestrator.service.docker.ContainerSession;
import dev.smithyai.orchestrator.service.forgejo.ForgejoClient;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class AttachmentHelper {

    private static final String ATTACHMENTS_DIR = "/workspace/.smithy/tmp/attachments";

    private AttachmentHelper() {}

    public static List<String> fetchAndInject(
        ForgejoClient client,
        ContainerSession session,
        String owner,
        String repo,
        int issueNumber
    ) {
        var allAttachments = new ArrayList<Attachment>();

        // Issue-level attachments
        try {
            allAttachments.addAll(client.getIssueAttachments(owner, repo, issueNumber));
        } catch (Exception e) {
            log.warn("Failed to fetch issue attachments for #{}", issueNumber, e);
        }

        // Comment-level attachments
        try {
            var comments = client.getIssueComments(owner, repo, issueNumber);
            for (var comment : comments) {
                try {
                    allAttachments.addAll(client.getCommentAttachments(owner, repo, comment.getId()));
                } catch (Exception e) {
                    log.warn("Failed to fetch attachments for comment {}", comment.getId(), e);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch comments for issue #{}", issueNumber, e);
        }

        if (allAttachments.isEmpty()) return List.of();

        // Create attachments directory and exclude from git
        var mkdirResult = session.exec(
            List.of(
                "sh",
                "-c",
                "mkdir -p \"" +
                    ATTACHMENTS_DIR +
                    "\"" +
                    " && grep -qxF \".smithy/tmp/\" .git/info/exclude 2>/dev/null" +
                    " || echo \".smithy/tmp/\" >> .git/info/exclude"
            )
        );
        if (mkdirResult.exitCode() != 0) {
            log.warn("Failed to create attachments directory: {}", mkdirResult.stderr());
        }

        var paths = new ArrayList<String>();
        for (var attachment : allAttachments) {
            String filename = attachment.getId() + "-" + attachment.getName();
            String containerPath = ATTACHMENTS_DIR + "/" + filename;
            try {
                byte[] data = client.downloadAttachment(attachment.getBrowserDownloadUrl());
                session.copyToContainer(ATTACHMENTS_DIR, data, filename);
                paths.add(containerPath);
                log.debug("Injected attachment {} into {}", filename, session.getContainerName());
            } catch (Exception e) {
                log.warn("Failed to download/inject attachment {} for issue #{}", attachment.getName(), issueNumber, e);
            }
        }

        if (!paths.isEmpty()) {
            log.info(
                "Injected {} attachment(s) into {} for issue #{}",
                paths.size(),
                session.getContainerName(),
                issueNumber
            );
        }
        return paths;
    }
}
