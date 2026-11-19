package com.github.oinsio.gnomish.adapter.check.http;

import com.github.oinsio.gnomish.adapter.check.NoCheckDetails;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.app.workspace.RecordedAttemptCommitWorkspace;
import com.github.oinsio.gnomish.domain.egress.EgressRefusal;
import com.github.oinsio.gnomish.domain.egress.EgressRefusedException;
import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.domain.engine.PollStatus;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.httpegress.EgressAllowlist;
import com.github.oinsio.gnomish.httpegress.ExchangeLimitException;
import com.github.oinsio.gnomish.httpegress.GuardedHttpCheckExchange;
import com.github.oinsio.gnomish.httpegress.HttpCheckExchange;
import com.github.oinsio.gnomish.httpegress.JdkHttpCheckExchange;
import com.github.oinsio.gnomish.httpegress.OperatorEgress;
import com.github.oinsio.gnomish.logtext.LogText;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The judgement of the built-in {@code http} check: one poll is one request, and the verdict is
 * read off the response by the check's own declared conditions (FR9, FR10, design D4 of
 * add-plugin-architecture). {@link #probe} judges once; {@link #poll} renders the {@link
 * PollStatus} as it stands (no engine port since task 5.2 of migrate-verify-to-executor-contract) and {@link HttpExecutor} renders it as the executor contract's result
 * (FR6 of migrate-verify-to-executor-contract), so the two differ only in their medium.
 *
 * <p>{@code pending-when} matching means "not terminal yet" ({@link PollStatus.Running}); it is
 * asked before the pass question, so a service reporting "still running" with a non-2xx status is
 * not a failure. A check declaring no {@code pending-when} is a one-shot probe. Status only decides
 * a pass.
 *
 * <p>An exchange that cannot be completed — an unreachable or unknown host (design D16, the outcome
 * classes: transport, never a refusal), an outrun bound, an interrupt — a credential that will not
 * resolve, a {@code ${...}} reference this run cannot supply (NFR-S2), and a target the egress guard
 * refuses are {@link PollStatus.CannotVerify}, an infrastructure failure, never a quality {@link
 * PollStatus.Fail}. A refusal is logged here, where it becomes a verdict, through {@link LogText},
 * since its sentence quotes a redirect target a remote server chose (FR5 of
 * harden-logging-observability). Stateless across polls (NFR-R2 of add-external-check-github-actions).
 *
 * <p>Implements FR9, FR10, FR11 of add-plugin-architecture; FR5 of harden-logging-observability;
 * FR6, FR17 of migrate-verify-to-executor-contract.
 *
 * @param exchange the single network seam; the egress guard sits here (NFR-S2)
 * @param secrets the seam a check's named credential resolves through at request time (FR11)
 * @param runContext the run's allowlisted interpolation values (NFR-S2); {@link
 *     HttpRunContext#none()} outside a run, which makes any interpolating check fail closed
 */
public record HttpExternalCheckClient(HttpCheckExchange exchange, SecretsProvider secrets, HttpRunContext runContext) {

    private static final Logger log = LoggerFactory.getLogger(HttpExternalCheckClient.class);

    /** A client for a run that supplies no variables — every non-interpolating check is unaffected. */
    public HttpExternalCheckClient(HttpCheckExchange exchange, SecretsProvider secrets) {
        this(exchange, secrets, HttpRunContext.none());
    }

    /**
     * The client every production path builds: the JDK exchange guarded by the operator's {@code
     * factory.egress.in-process} scope, the {@code http} built-in's one admitted set (FR17, design
     * D16 of migrate-verify-to-executor-contract) — the substituted target of every poll, a templated
     * host included, is judged there at connect, before any socket.
     *
     * @param inProcess the operator's {@code in-process} scope; never null
     * @param secrets the seam a check's named credential resolves through; never null
     * @param runContext the run's allowlisted interpolation values; never null
     * @return the guarded client; never null
     */
    public static HttpExternalCheckClient guarded(
            OperatorEgress inProcess, SecretsProvider secrets, HttpRunContext runContext) {
        var exchange = new GuardedHttpCheckExchange(new JdkHttpCheckExchange(), EgressAllowlist.of(inProcess));
        return new HttpExternalCheckClient(exchange, secrets, runContext);
    }

    /** How much of a failing response's body travels into the findings, so a report stays readable. */
    static final int BODY_EXCERPT_LIMIT = 2000;

    /**
     * Polls the check's endpoint once and classifies what came back.
     *
     * @param check the external check to poll; its {@code params} carry the whole target
     * @param workspace the check's working copy, the attempt commit's source; may be null
     * @return the status this single poll observed; never null
     */
    public PollStatus poll(VerifyCheck.Executor check, Workspace workspace) {
        return probe(check.checkId(), check.settings(), workspace);
    }

    /**
     * Sends the check's request once and judges the response, the one judgement both renderings share.
     *
     * @param checkId the check's identity, for the finding and the refusal line; never null
     * @param settings the check's settings, graded at load; never null
     * @param workspace the check's working copy, carrying the attempt commit; may be null
     * @return the status this single poll observed; never null
     */
    PollStatus probe(String checkId, Map<String, Object> settings, @Nullable Workspace workspace) {
        HttpCheckParams params = HttpCheckParams.from(settings);
        HttpRequest request;
        try {
            request = HttpCheckRequest.build(
                    params, secrets, HttpCheckVariables.of(runContext, attemptCommit(workspace)));
        } catch (HttpCheckRequestException e) {
            return new PollStatus.CannotVerify(UntrustedText.manifest(e.reason()), NoCheckDetails.NO_DETAILS);
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
                    OperatorEvent.HTTP_CHECK_REFUSED_BY_EGRESS.head()
                            + "http check '{}' refused before the request left the factory: {}",
                    checkId,
                    LogText.forLog(refusal.describe()));
            // describe() interpolates the manifest's target raw, so it keeps that family; the
            // label is a constant the guard's own enum owns, so it is factory prose (design D3
            // of type-untrusted-text).
            return new PollStatus.CannotVerify(
                    UntrustedText.manifest(refusal.describe()),
                    UntrustedText.factory(refusal.reason().label()));
        } catch (ExchangeLimitException | IOException e) {
            // FR15, design D16 (the outcome classes): an outrun bound names the limit, an unknown host
            // is transport — infrastructure, never advice to grant a host.
            return cannotVerify(target, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return cannotVerify(target, e);
        }
        HttpCheckCondition pendingWhen = params.pendingWhen();
        if (pendingWhen != null && pendingWhen.matches(response.body())) {
            return new PollStatus.Running();
        }
        if (isSuccess(response.status()) && params.passWhen().matches(response.body())) {
            return new PollStatus.Pass(target);
        }
        return new PollStatus.Fail(List.of(failure(checkId, params, target, response)));
    }

    /**
     * The attempt commit of the round under verification, or {@code null} when the workspace carries
     * none (a plain directory, a round not yet closed): a check interpolating it then fails closed.
     */
    private static @Nullable String attemptCommit(@Nullable Workspace workspace) {
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
     * The finding a failing check feeds back: what was expected, what came back and a body excerpt —
     * the response only, never the request, so no credential travels into a report (NFR-S1).
     */
    private static Finding failure(
            String checkId, HttpCheckParams params, String target, HttpCheckExchange.Response response) {
        return new Finding(
                "http check '%s' did not pass: expected %s, got HTTP %d"
                        .formatted(checkId, params.passWhen().describe(), response.status()),
                target,
                excerpt(response.body()));
    }

    /** A CannotVerify naming the endpoint and preserving the cause as details (NFR-O1). */
    private static PollStatus.CannotVerify cannotVerify(String target, Exception cause) {
        // The fold re-mints (design D5 of type-untrusted-text): the message quotes the endpoint the
        // manifest named and how it answered, so it keeps that family, as the reason beside it does.
        UntrustedText detail = UntrustedText.manifest(cause.getClass().getName() + ": " + cause.getMessage());
        return new PollStatus.CannotVerify(UntrustedText.manifest("http check could not reach " + target), detail);
    }

    private static String excerpt(String body) {
        return body.length() <= BODY_EXCERPT_LIMIT ? body : body.substring(0, BODY_EXCERPT_LIMIT) + "…";
    }
}
