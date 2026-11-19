package com.github.oinsio.gnomish.adapter.scm.status;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parses the two listings the {@code scm-status} built-in reads into context reports, keeping only
 * the reports on the attempt SHA (FR4, FR9 of migrate-verify-to-executor-contract): a check run
 * whose {@code head_sha} is another commit, and every status of a combined status whose {@code sha}
 * is another commit, are dropped as if absent — a report on the previous attempt's commit keeps an
 * expected context waiting, never passes it.
 *
 * <p>The commit statuses are read from the combined-status listing, which GitHub and Gitea serve
 * under one path; GitHub names a status's state {@code state}, Gitea {@code status}, and both are
 * read. The platform's words about a report — its state and description, or a check run's
 * conclusion and output title — travel as the platform wrote them and are minted in the tracker
 * family by the one result that quotes them ({@code ScmStatusResults}).
 *
 * <p>Implements FR4, FR9 of migrate-verify-to-executor-contract.
 */
final class ScmContextParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> PASSED = Set.of("success", "neutral", "skipped");
    private static final Set<String> RUNNING_STATUSES = Set.of("pending");
    private static final String WARNING = "warning";
    private static final String COMPLETED = "completed";

    private ScmContextParser() {}

    /** The check runs of a {@code /commits/<sha>/check-runs} body that name the attempt SHA. */
    static List<ScmContext> checkRuns(String body, AttemptSha sha) {
        List<ScmContext> reports = new ArrayList<>();
        for (JsonNode run : tree(body, "check runs").path("check_runs")) {
            if (sha.names(run.path("head_sha").asText(""))) {
                String status = run.path("status").asText("");
                String conclusion = run.path("conclusion").asText("");
                ScmContextState state = COMPLETED.equals(status) ? completed(conclusion) : ScmContextState.RUNNING;
                String words = COMPLETED.equals(status) ? conclusion : status;
                reports.add(report(
                        ScmContext.Source.CHECK_RUN,
                        run,
                        "name",
                        state,
                        words,
                        run.path("output").path("title")));
            }
        }
        return reports;
    }

    /** The statuses of a {@code /commits/<sha>/status} body, when the body names the attempt SHA. */
    static List<ScmContext> statuses(String body, AttemptSha sha) {
        JsonNode root = tree(body, "combined status");
        JsonNode pinned = sha.names(root.path("sha").asText("")) ? root.path("statuses") : MissingNode.getInstance();
        List<ScmContext> reports = new ArrayList<>();
        for (JsonNode status : pinned) {
            String word = status.path("state").asText(status.path("status").asText(""));
            reports.add(report(
                    ScmContext.Source.STATUS, status, "context", stateOf(word), word, status.path("description")));
        }
        return reports;
    }

    private static ScmContextState completed(String conclusion) {
        return PASSED.contains(conclusion) ? ScmContextState.PASSED : ScmContextState.FAILED;
    }

    private static ScmContextState stateOf(String word) {
        if (PASSED.contains(word)) {
            return ScmContextState.PASSED;
        }
        if (RUNNING_STATUSES.contains(word)) {
            return ScmContextState.RUNNING;
        }
        return WARNING.equals(word) ? ScmContextState.WARNED : ScmContextState.FAILED;
    }

    private static ScmContext report(
            ScmContext.Source source,
            JsonNode node,
            String nameField,
            ScmContextState state,
            String word,
            JsonNode said) {
        String description = said.asText("");
        String detail = description.isBlank() ? word : word + ": " + description;
        return new ScmContext(
                source, node.path(nameField).asText(""), state, node.path("id").asLong(), detail);
    }

    private static JsonNode tree(String body, String listing) {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || !root.isObject()) {
                throw new ScmStatusReadException("the SCM answered the " + listing + " listing with no JSON object");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new ScmStatusReadException("the SCM answered the " + listing + " listing with malformed JSON", e);
        }
    }
}
