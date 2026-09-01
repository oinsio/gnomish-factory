package com.github.oinsio.gnomish.adapter.check.http;

import com.github.oinsio.gnomish.app.CheckRunContext;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.app.workspace.RecordedAttemptCommitWorkspace;
import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.domain.engine.PollStatus;
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.logtext.LogText;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The generic {@link ExternalCheckClient} of the built-in {@code http} provider: one poll is one
 * request, and the verdict is read off the response by the check's own declared conditions (FR9,
 * FR10, design D4 of add-plugin-architecture).
 *
 * <p>The poll loop is the engine's, unchanged. This client only classifies a single observation:
 * {@code pending-when} matching means "not terminal yet" ({@link PollStatus.Running}), which is what
 * makes the engine poll again at the check's {@code interval} and classify a {@code timeout} by the
 * check's own {@code timeoutClass}; a check declaring no {@code pending-when} therefore reaches a
 * verdict on its first response — a one-shot probe, which is a degenerate poll rather than a second
 * code path.
 *
 * <p>The pending question is asked before the pass question deliberately: a service that reports
 * "still running" with a non-2xx status (a 202, a 404 for a not-yet-created report) would otherwise
 * be a failure on its first poll. Status only decides a pass.
 *
 * <p>An exchange that cannot be completed, or a credential that will not resolve, is {@link
 * PollStatus.CannotVerify} — an infrastructure failure that burns no stage attempt — never a
 * quality {@link PollStatus.Fail}: the endpoint being unreachable says nothing about the artifact.
 *
 * <p>Stateless across polls: no response, verdict, or resolved secret is retained, so two instances
 * polling the same check observe the same thing and either may resume the other's task (NFR-R2 of
 * add-external-check-github-actions, kept as a port-wide property).
 *
 * <p>A refusal is the one degradation the operator cannot read off the endpoint: the request never
 * left the factory, so nothing on the other side records it. Both refusal classes — a target the
 * allowlist rejects and a redirect chain that outran its bound — are logged here, at the layer that
 * turns them into a verdict, so the fault has exactly one line (FR5 of
 * harden-logging-observability). The refusal's own sentence names a redirect target chosen by a
 * remote server, so it goes through {@link LogText} like any other text from outside.
 *
 * <p>Implements FR9, FR10, FR11 of add-plugin-architecture; FR5 of harden-logging-observability.
 *
 * <p>Two ways a poll can fail before the network: a credential that will not resolve, and a {@code
 * ${...}} reference this run cannot supply (NFR-S2). Both are {@code CannotVerify} naming what was
 * missing — never a silent substitution, since a URL built from a missing value observes the wrong
 * thing. A target the egress allowlist refuses arrives the same way, from the guarded exchange.
 *
 * @param exchange the single network seam; the egress guard sits here (NFR-S2)
 * @param secrets the seam a check's named credential resolves through at request time (FR11)
 * @param runContext the run's whitelisted interpolation values (NFR-S2); {@link
 *     CheckRunContext#none()} outside a run, which makes any interpolating check fail closed
 */
public record HttpExternalCheckClient(HttpCheckExchange exchange, SecretsProvider secrets, CheckRunContext runContext)
        implements ExternalCheckClient {

    private static final Logger log = LoggerFactory.getLogger(HttpExternalCheckClient.class);

    /** A client for a run that supplies no variables — every non-interpolating check is unaffected. */
    public HttpExternalCheckClient(HttpCheckExchange exchange, SecretsProvider secrets) {
        this(exchange, secrets, CheckRunContext.none());
    }

    /** How much of a failing response's body travels into the findings, so a report stays readable. */
    static final int BODY_EXCERPT_LIMIT = 2000;

    /**
     * Polls the check's endpoint once and classifies what came back.
     *
     * @param check the external check to poll; its {@code params} carry the whole target
     * @param workspace unused — an http target is addressed by the manifest, not by the working
     *     copy; the parameter stays because it is the engine's port signature
     * @return the status this single poll observed; never null
     */
    @Override
    public PollStatus poll(VerifyCheck.External check, Workspace workspace) {
        HttpCheckParams params = HttpCheckParams.from(check.params());
        HttpRequest request;
        try {
            request = HttpCheckRequest.build(
                    params, secrets, HttpCheckVariables.of(runContext, attemptCommit(workspace)));
        } catch (HttpCheckCredentialException e) {
            return new PollStatus.CannotVerify(e.reason(), "");
        } catch (HttpCheckVariableException e) {
            return new PollStatus.CannotVerify(e.reason(), "");
        }
        String target = request.uri().toString();
        HttpCheckExchange.Response response;
        try {
            response = exchange.send(request);
        } catch (EgressRefusedException e) {
            EgressRefusal refusal = e.refusal();
            // throwable-not-subject: the refusal is a guard decision, not a fault — every fact is
            //     in its own sentence, and the exception exists only to unwind the hop.
            log.warn(
                    "http check '{}' refused before the request left the factory: {}",
                    check.checkId(),
                    LogText.forLog(refusal.describe()));
            return new PollStatus.CannotVerify(
                    refusal.describe(), refusal.reason().label());
        } catch (IOException e) {
            return cannotVerify(target, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return cannotVerify(target, e);
        }
        if (params.pendingWhen() != null && params.pendingWhen().matches(response.body())) {
            return new PollStatus.Running();
        }
        if (isSuccess(response.status()) && params.passWhen().matches(response.body())) {
            return new PollStatus.Pass(target);
        }
        return new PollStatus.Fail(List.of(failure(check, params, target, response)));
    }

    /**
     * The attempt commit of the round under verification, or {@code null} when the workspace carries
     * none — a manual run over a plain directory, or a round not yet closed by a snapshot. Absent, a
     * check interpolating {@code ${attempt.commit}} fails closed rather than addressing whatever a
     * placeholder would.
     */
    private static @Nullable String attemptCommit(Workspace workspace) {
        if (!(workspace instanceof RecordedAttemptCommitWorkspace attemptWorkspace)) {
            return null;
        }
        try {
            return attemptWorkspace.attemptCommitSha();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /** 2xx — the default pass condition every declared {@code pass-when} narrows (FR10). */
    private static boolean isSuccess(int status) {
        return status / 100 == 2;
    }

    /**
     * The finding a failing check feeds back into the next stage attempt: what was expected, what
     * came back, and an excerpt of the body so an author can see why — the response only, never the
     * request, so no credential can travel into a committed report (NFR-S1).
     */
    private static Finding failure(
            VerifyCheck.External check, HttpCheckParams params, String target, HttpCheckExchange.Response response) {
        return new Finding(
                "http check '%s' did not pass: expected %s, got HTTP %d"
                        .formatted(check.checkId(), params.passWhen().describe(), response.status()),
                target,
                excerpt(response.body()));
    }

    /** A CannotVerify naming the endpoint and preserving the cause as details (NFR-O1). */
    private static PollStatus.CannotVerify cannotVerify(String target, Exception cause) {
        return new PollStatus.CannotVerify(
                "http check could not reach " + target, cause.getClass().getName() + ": " + cause.getMessage());
    }

    private static String excerpt(String body) {
        return body.length() <= BODY_EXCERPT_LIMIT ? body : body.substring(0, BODY_EXCERPT_LIMIT) + "…";
    }
}
