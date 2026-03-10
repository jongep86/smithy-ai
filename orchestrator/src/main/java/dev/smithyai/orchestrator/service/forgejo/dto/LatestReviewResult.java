package dev.smithyai.orchestrator.service.forgejo.dto;

import dev.smithyai.forgejoclient.model.PullReviewComment;
import java.util.List;

public record LatestReviewResult(List<PullReviewComment> comments, String reviewBody) {}
