package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.check.http.HttpExternalCheckClient
import com.github.oinsio.gnomish.adapter.check.http.HttpRunContext
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.httpegress.HttpCheckExchange
import com.github.oinsio.gnomish.httpegress.OperatorEgress
import java.time.InstantSource

/**
 * The built-ins' registry collaborators for a spec that builds an {@link ExecutorRegistry} by hand:
 * the in-process runners on {@code clock}, the {@code http} judgement guarded by the operator scope
 * the spec supplies, and the secrets port the spec supplies (both empty by default).
 */
final class BuiltInEntriesFixture {

    private BuiltInEntriesFixture() {}

    static BuiltInEntries of(InstantSource clock, Map<String, String> secretValues = [:], List<String> inProcess = []) {
        SecretsProvider secrets = secrets(secretValues)
        new BuiltInEntries(new FilesExistCheckRunner(), new ShellCommandCheckRunner(clock),
                HttpExternalCheckClient.guarded(new OperatorEgress(inProcess), secrets, HttpRunContext.none()), secrets, clock)
    }

    /** The same, with the {@code http} judgement over a scripted exchange instead of the guarded JDK one. */
    static BuiltInEntries scripted(InstantSource clock, Map<String, String> secretValues, HttpCheckExchange exchange) {
        SecretsProvider secrets = secrets(secretValues)
        new BuiltInEntries(new FilesExistCheckRunner(), new ShellCommandCheckRunner(clock),
                new HttpExternalCheckClient(exchange, secrets), secrets, clock)
    }

    private static SecretsProvider secrets(Map<String, String> values) {
        { String name -> Optional.ofNullable(values[name]) } as SecretsProvider
    }
}
