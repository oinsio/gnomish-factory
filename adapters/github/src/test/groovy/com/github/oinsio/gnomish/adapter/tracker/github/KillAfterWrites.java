package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fault injection for the tracker kill-point harness (task 9.1b, FR19, M1 of
 * harden-task-branch-contract): wraps the stateful GitHub fixture and fails the connection on
 * every mutating request past a budget, WITHOUT delegating — so the refused write never reaches
 * the fixture registry and the frozen state is exactly what a process death between two writes
 * leaves behind.
 *
 * <p>A wrapper rather than a second registered extension: WireMock applies transformers in an
 * order this spec must not depend on, and a fault injected after the fixture transformer already
 * ran would leave the write applied — the opposite of the window under test.
 *
 * <p>Test-only: never shipped.
 */
final class KillAfterWrites implements ResponseDefinitionTransformerV2 {

    private final FixtureGithubTransformer delegate;
    private final AtomicInteger budget = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicInteger writes = new AtomicInteger();

    KillAfterWrites(FixtureGithubTransformer delegate) {
        this.delegate = delegate;
    }

    /** Lets {@code writes} mutating requests through, then fails the connection on every later one. */
    void killAfter(int allowed) {
        budget.set(allowed);
        writes.set(0);
    }

    /** Lets every request through — the counting pass, and the fact reads after a kill. */
    void noKill() {
        budget.set(Integer.MAX_VALUE);
        writes.set(0);
    }

    /** How many mutating requests this transformer has seen since the last arming. */
    int writesSeen() {
        return writes.get();
    }

    @Override
    public String getName() {
        return "kill-after-writes";
    }

    @Override
    public ResponseDefinition transform(ServeEvent serveEvent) {
        boolean mutating = !RequestMethod.GET.equals(serveEvent.getRequest().getMethod());
        if (mutating && writes.incrementAndGet() > budget.get()) {
            return ResponseDefinitionBuilder.responseDefinition()
                    .withFault(Fault.CONNECTION_RESET_BY_PEER)
                    .build();
        }
        return delegate.transform(serveEvent);
    }
}
