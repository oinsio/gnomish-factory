package com.github.oinsio.gnomish.adapter.check.http;

import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.BuiltInExecutors;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.executorcontract.Capability;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.EnforcementLevel;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.pendingkey.PendingKey;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import java.util.Map;

/**
 * The {@code http} built-in verdict executor (FR6 of migrate-verify-to-executor-contract, design
 * D4): the status-code probe with declared conditions, answering the executor contract. It is built
 * over the check it judges and the check's working copy, as the other verdict built-ins are, and
 * over the one judgement of a request ({@link HttpCheckProbe#probe}).
 *
 * <p>A {@code verdict} run sends the request its settings declare, the run variables interpolated
 * (NFR-S2 of add-plugin-architecture), through the guarded exchange: the substituted target is
 * judged against the operator's {@code factory.egress.in-process} scope at connect, before any
 * socket, a templated host included (design D16). A response matching {@code pending-when} is
 * {@code pending}, and a {@link #poll} sends the same request again with no other state; a terminal
 * response is {@code completed} with SARIF ({@link HttpResults}) and the engine derives the verdict
 * (design D5); a refusal, an unknown or unreachable host, an outrun bound, a credential or a
 * variable this run cannot supply are {@code failed/infrastructure} naming why (NG9: no condition).
 *
 * <p>The describe declares role {@code verdict}, the {@code poll} capability, the settings schema
 * of {@link HttpSettings}, no credential — a check names its own in {@code auth}, resolved at
 * request time — and no egress: the hosts it may reach are the operator's scope alone. The run
 * mediates no model, so its usage is a known zero (design D13).
 *
 * <p>Implements FR6, FR17, NFR-C1 of migrate-verify-to-executor-contract.
 */
public final class HttpExecutor implements Executor {

    /** The built-in's name in the executor registry, the one a {@code provider: http} check maps to. */
    public static final String NAME = BuiltInExecutors.HTTP;

    private final HttpCheckProbe client;
    private final VerifyCheck.Executor check;
    private final Workspace workspace;

    /**
     * @param client the judgement over the guarded exchange ({@link HttpCheckProbe#guarded});
     *     never null
     * @param check the check this executor judges; never null
     * @param workspace the check's working copy, carrying the attempt commit; never null
     */
    public HttpExecutor(HttpCheckProbe client, VerifyCheck.Executor check, Workspace workspace) {
        this.client = client;
        this.check = check;
        this.workspace = workspace;
    }

    /**
     * The credential name {@code check}'s own {@code auth} setting names, if any (FR11, FR17 of
     * add-plugin-architecture): the describe declares none, since each check names its own, so this
     * is the one reading of that name the run's scrub and never-allowlist sets take it from (task
     * 5.2 of migrate-verify-to-executor-contract). The settings were graded at load ({@link
     * HttpCheckParamsValidator}).
     *
     * @param check an {@code http} check; never null
     * @return the credential name its settings name, or empty for an unauthenticated target
     */
    public static List<String> credentials(VerifyCheck.Executor check) {
        HttpCheckParams.Auth auth = HttpCheckParams.from(check.settings()).auth();
        return auth == null ? List.of() : List.of(auth.credential());
    }

    @Override
    public Describe describe() {
        return describeAnswer();
    }

    /**
     * The describe answer, the same for every binding: what the executor registry entry carries
     * before any check or round is bound (design D18 of migrate-verify-to-executor-contract).
     *
     * @return a fresh answer; never null
     */
    public static Describe describeAnswer() {
        return new Describe(
                Describe.SPOKEN_PROTOCOL_VERSION,
                UntrustedText.factory(BuiltInExecutors.VERSION),
                List.of(Role.VERDICT),
                List.of(Capability.POLL),
                HttpSettings.schema(),
                List.of(),
                List.of(),
                null,
                List.of(),
                EnforcementLevel.PROMPT_ONLY,
                true,
                false);
    }

    @Override
    public ExecutorResult start(ExecutorRequest request) {
        if (request.role() != Role.VERDICT) {
            return HttpResults.failed(UntrustedText.factory(NAME + " answers verdict runs only, and was asked for a "
                    + request.role().wire() + " run"));
        }
        Map<String, Object> settings = ExecutorContractJson.settingsValues(request.settings());
        UntrustedText refused = HttpSettings.refusal(settings);
        if (refused != null) {
            return HttpResults.failed(refused);
        }
        return HttpResults.of(client.probe(check.checkId(), settings, workspace));
    }

    /** Stateless: a poll is the same request again, whatever key the pending run was given. */
    @Override
    public ExecutorResult poll(ExecutorRequest request, PendingKey key) {
        return start(request);
    }
}
