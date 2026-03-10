package dev.smithyai.orchestrator.service.forgejo;

import dev.smithyai.forgejoclient.ApiClient;
import dev.smithyai.forgejoclient.ApiException;
import dev.smithyai.forgejoclient.api.IssueApi;
import dev.smithyai.forgejoclient.api.RepositoryApi;
import dev.smithyai.forgejoclient.model.*;
import dev.smithyai.orchestrator.service.forgejo.dto.LatestReviewResult;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

@Slf4j
public class ForgejoClient {

    private final String baseUrl;
    private final IssueApi issueApi;
    private final RepositoryApi repoApi;
    private final RestClient rest;

    public ForgejoClient(String baseUrl, String token) {
        this.baseUrl = baseUrl;

        var apiClient = new ApiClient();
        apiClient.setBasePath(baseUrl + "/api/v1");
        apiClient.addDefaultHeader("Authorization", "token " + token);

        this.issueApi = new IssueApi(apiClient);
        this.repoApi = new RepositoryApi(apiClient);

        // Keep a RestClient for downloadAttachment (URL rewriting not in SDK)
        this.rest = RestClient.builder().defaultHeader("Authorization", "token " + token).build();
    }

    @FunctionalInterface
    private interface ApiCall<T> {
        T call() throws ApiException;
    }

    @FunctionalInterface
    private interface ApiVoidCall {
        void call() throws ApiException;
    }

    private <T> T api(ApiCall<T> call) {
        try {
            return call.call();
        } catch (ApiException e) {
            throw new ForgejoApiException(e.getCode(), e.getMessage(), e);
        }
    }

    private void apiVoid(ApiVoidCall call) {
        try {
            call.call();
        } catch (ApiException e) {
            throw new ForgejoApiException(e.getCode(), e.getMessage(), e);
        }
    }

    // ── Issues ──────────────────────────────────────────────

    public Issue getIssue(String owner, String repo, int number) {
        return api(() -> issueApi.issueGetIssue(owner, repo, (long) number));
    }

    public List<Comment> getIssueComments(String owner, String repo, int number) {
        return api(() -> issueApi.issueGetComments(owner, repo, (long) number, null, null));
    }

    public List<Comment> getIssueCommentsSince(String owner, String repo, int number, OffsetDateTime since) {
        return api(() -> issueApi.issueGetComments(owner, repo, (long) number, since, null));
    }

    public Comment createIssueComment(String owner, String repo, int number, String body) {
        return api(() ->
            issueApi.issueCreateComment(owner, repo, (long) number, new CreateIssueCommentOption().body(body))
        );
    }

    // ── Attachments ─────────────────────────────────────────

    public List<Attachment> getIssueAttachments(String owner, String repo, int number) {
        return api(() -> issueApi.issueListIssueAttachments(owner, repo, (long) number));
    }

    public List<Attachment> getCommentAttachments(String owner, String repo, long commentId) {
        return api(() -> issueApi.issueListIssueCommentAttachments(owner, repo, commentId));
    }

    public byte[] downloadAttachment(String downloadUrl) {
        // Rewrite host to internal Forgejo URL
        URI publicUri = URI.create(downloadUrl);
        String internalUrl = downloadUrl.replaceFirst(
            java.util.regex.Pattern.quote(publicUri.getScheme() + "://" + publicUri.getAuthority()),
            baseUrl
        );

        return rest.get().uri(URI.create(internalUrl)).retrieve().body(byte[].class);
    }

    // ── Pull Requests ───────────────────────────────────────

    public PullRequest createPullRequest(
        String owner,
        String repo,
        String title,
        String head,
        String base,
        String body,
        boolean draft
    ) {
        String prTitle = draft ? "WIP: " + title : title;
        var opt = new CreatePullRequestOption();
        opt.setTitle(prTitle);
        opt.setHead(head);
        opt.setBase(base);
        opt.setBody(body);
        return api(() -> repoApi.repoCreatePullRequest(owner, repo, opt));
    }

    public PullRequest getPullRequest(String owner, String repo, int number) {
        return api(() -> repoApi.repoGetPullRequest(owner, repo, (long) number));
    }

    public List<PullReviewComment> getPrComments(String owner, String repo, int prNumber) {
        List<PullReview> reviews = api(() -> repoApi.repoListPullReviews(owner, repo, (long) prNumber, null, null));
        var comments = new ArrayList<PullReviewComment>();
        for (var review : reviews) {
            long reviewId = review.getId();
            try {
                comments.addAll(getReviewComments(owner, repo, prNumber, reviewId));
            } catch (Exception e) {
                log.warn("Failed to fetch comments for review {}", reviewId, e);
            }
        }
        return comments;
    }

    public List<PullReviewComment> getReviewComments(String owner, String repo, int prNumber, long reviewId) {
        return api(() -> repoApi.repoGetPullReviewComments(owner, repo, (long) prNumber, reviewId));
    }

    public LatestReviewResult getLatestReviewComments(String owner, String repo, int prNumber, String reviewer) {
        List<PullReview> reviews = api(() -> repoApi.repoListPullReviews(owner, repo, (long) prNumber, null, null));

        PullReview target = null;
        for (int i = reviews.size() - 1; i >= 0; i--) {
            var r = reviews.get(i);
            if (r.getUser() != null && reviewer.equals(r.getUser().getLogin())) {
                target = r;
                break;
            }
        }
        if (target == null) return new LatestReviewResult(List.of(), "");

        long reviewId = target.getId();
        String reviewBody = target.getBody() != null ? target.getBody() : "";
        var comments = getReviewComments(owner, repo, prNumber, reviewId);
        return new LatestReviewResult(comments, reviewBody);
    }

    // ── Assignees & Reviewers ────────────────────────────────

    public void setIssueAssignees(String owner, String repo, int issueNumber, List<String> assignees) {
        var opt = new EditIssueOption();
        opt.setAssignees(assignees);
        apiVoid(() -> issueApi.issueEditIssue(owner, repo, (long) issueNumber, opt));
    }

    public void requestReview(String owner, String repo, int prNumber, List<String> reviewers) {
        apiVoid(() ->
            repoApi.repoCreatePullReviewRequests(
                owner,
                repo,
                (long) prNumber,
                new PullReviewRequestOptions().reviewers(reviewers)
            )
        );
    }

    public boolean isAssigned(String owner, String repo, int prNumber, String username) {
        var pr = getPullRequest(owner, repo, prNumber);
        return (
            pr.getAssignees() != null &&
            pr
                .getAssignees()
                .stream()
                .anyMatch(a -> username.equals(a.getLogin()))
        );
    }

    // ── Reviews ─────────────────────────────────────────────

    public void createPullReview(
        String owner,
        String repo,
        int prNumber,
        String body,
        String event,
        List<Map<String, Object>> comments
    ) {
        var opt = new CreatePullReviewOptions();
        opt.setBody(body);
        opt.setEvent(event != null ? event : "COMMENT");
        if (comments != null && !comments.isEmpty()) {
            var reviewComments = new ArrayList<CreatePullReviewComment>();
            for (var c : comments) {
                var rc = new CreatePullReviewComment();
                rc.setPath((String) c.get("path"));
                rc.setBody((String) c.get("body"));
                Object pos = c.get("new_position");
                if (pos instanceof Number n) {
                    rc.setNewPosition(n.longValue());
                }
                reviewComments.add(rc);
            }
            opt.setComments(reviewComments);
        }
        apiVoid(() -> repoApi.repoCreatePullReview(owner, repo, (long) prNumber, opt));
    }

    public List<PullReview> getPrReviews(String owner, String repo, int prNumber) {
        return api(() -> repoApi.repoListPullReviews(owner, repo, (long) prNumber, null, null));
    }

    // ── Repository ──────────────────────────────────────────

    public boolean repoExists(String owner, String repo) {
        try {
            api(() -> repoApi.repoGet(owner, repo));
            return true;
        } catch (ForgejoApiException e) {
            if (e.isNotFound()) return false;
            throw e;
        }
    }

    // ── Queries ─────────────────────────────────────────────

    public PullRequest findPrByHead(String owner, String repo, String head) {
        List<PullRequest> prs = api(() ->
            repoApi.repoListPullRequests(owner, repo, "open", null, null, null, null, null, null)
        );
        for (var pr : prs) {
            if (pr.getHead() != null && head.equals(pr.getHead().getRef())) {
                return pr;
            }
        }
        return null;
    }
}
