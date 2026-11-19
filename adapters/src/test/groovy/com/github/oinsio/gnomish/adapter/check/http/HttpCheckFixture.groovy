package com.github.oinsio.gnomish.adapter.check.http

import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.pipeline.BuiltInExecutors
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.executorcontract.Answer
import com.github.oinsio.gnomish.executorcontract.AnswerReading
import com.github.oinsio.gnomish.executorcontract.Bounds
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.SarifResult
import com.github.oinsio.gnomish.httpegress.HttpCheckExchange
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.net.http.HttpRequest
import java.time.Duration

/**
 * Shared staging for the http provider's specs: a check carrying the given params, a secrets
 * provider over a literal map, and a scripted exchange that records the request it was handed —
 * enough to assert the composed request without opening a socket, since {@link HttpCheckExchange}
 * is the provider's one network seam. {@link #run} drives the {@code http} built-in executor over
 * the same staging, sending the params as a {@code verdict} request's settings (FR6 of
 * migrate-verify-to-executor-contract), and {@link #results} reads its SARIF answer back.
 */
trait HttpCheckFixture {

    static final String URL = 'https://ci.example.invalid/api/status'

    /** An external check selecting the http provider, with the given per-check params. */
    VerifyCheck.Executor check(Map<String, Object> params, String checkId = 'quality-gate') {
        VerifyChecks.external(
                checkId, BuiltInExecutors.HTTP, params, Duration.ofSeconds(1),
                Duration.ofSeconds(30), VerifyCheck.TimeoutClass.QUALITY, [])
    }

    /** Runs the provider against a scripted exchange, outside any run context or workspace. */
    PollStatus poll(Map<String, Object> params, ScriptedExchange exchange) {
        poll(params, exchange, [:])
    }

    /** The same run, with the named secrets the manifest's auth section may resolve against. */
    PollStatus poll(Map<String, Object> params, ScriptedExchange exchange, Map<String, String> secrets) {
        new HttpExternalCheckClient(exchange, providing(secrets)).poll(check(params), null)
    }

    /** Runs the built-in executor against a scripted exchange, outside any run context or workspace. */
    ExecutorResult run(Map<String, Object> params, ScriptedExchange exchange, Map<String, String> secrets = [:]) {
        executor(exchange, secrets, HttpRunContext.none(), null).start(verdict(params))
    }

    /** The built-in executor over a scripted exchange, with the given run context and working copy. */
    HttpExecutor executor(HttpCheckExchange exchange, Map<String, String> secrets, HttpRunContext runContext,
            Workspace workspace) {
        new HttpExecutor(new HttpExternalCheckClient(exchange, providing(secrets), runContext), check([:]), workspace)
    }

    /** A {@code verdict} request carrying {@code settings} as the law loader maps them. */
    static ExecutorRequest verdict(Map<String, ?> settings) {
        new ExecutorRequest(Role.VERDICT, new com.github.oinsio.gnomish.executorcontract.Workspace('.'),
                new Policy.ReadOnly(), RoleInputs.verdict('http'), ExecutorContractJson.settingsTree(settings),
                Bounds.none(), [], null)
    }

    /** The results of every run of a completed result's SARIF answer, in the order written. */
    static List<SarifResult> results(ExecutorResult result) {
        def reading = Answer.read(result.answer(), { String text ->
            UntrustedText.factory(text)
        })
        assert reading instanceof AnswerReading.Parsed
        ((reading as AnswerReading.Parsed).answer() as Answer.Verdict).runs().collectMany {
            it.results()
        }
    }

    SecretsProvider providing(Map<String, String> secrets) {
        { name -> Optional.ofNullable(secrets[name]) } as SecretsProvider
    }

    /** An exchange answering every request the same way, remembering the last one it saw. */
    static class ScriptedExchange implements HttpCheckExchange {

        int status
        String body
        Exception failure
        HttpRequest lastRequest

        ScriptedExchange(int status, String body) {
            this.status = status
            this.body = body
        }

        ScriptedExchange(Exception failure) {
            this.failure = failure
        }

        @Override
        Response send(HttpRequest request) throws IOException, InterruptedException {
            lastRequest = request
            if (failure != null) {
                throw failure
            }
            new Response(status, body)
        }
    }
}
