package com.github.oinsio.gnomish.adapter.check.http

import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.net.http.HttpRequest
import java.time.Duration

/**
 * Shared staging for the http provider's specs: a check carrying the given params, a secrets
 * provider over a literal map, and a scripted exchange that records the request it was handed —
 * enough to assert the composed request without opening a socket, since {@link HttpCheckExchange}
 * is the provider's one network seam.
 */
trait HttpCheckFixture {

    static final String URL = 'https://ci.example.invalid/api/status'

    /** An external check selecting the http provider, with the given per-check params. */
    VerifyCheck.External check(Map<String, Object> params, String checkId = 'quality-gate') {
        new VerifyCheck.External(
                checkId, HttpCheckClientFactory.PROVIDER, params, Duration.ofSeconds(1),
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
