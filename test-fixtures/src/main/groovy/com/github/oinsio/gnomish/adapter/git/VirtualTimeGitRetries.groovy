package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualSleeper

/**
 * The git-adapter half of {@code VirtualTimeRetries}: the bounded infrastructure retry
 * (default-branch discovery, base refresh, remote reads) with the production attempt count and
 * backoff measured on a virtual clock, so a spec asserts the real shape and an outage exhausts the
 * bound in microseconds ({@code .claude/rules/testing.md}, "Time is injected in tests").
 *
 * <p>Here rather than beside its terminal-write sibling because that one lives in a {@code
 * ..domain..} package, and the domain-purity gate forbids a {@code ..domain..} class from naming an
 * adapter type — which a factory returning {@link GitInfrastructureRetry} necessarily does. The
 * split is the rule's, not a preference.
 */
final class VirtualTimeGitRetries {

    private VirtualTimeGitRetries() {}

    /** The production git infrastructure retry, on virtual time. */
    static GitInfrastructureRetry gitInfrastructure() {
        def clock = new VirtualClock()
        new GitInfrastructureRetry(new VirtualSleeper(clock), GitInfrastructureRetry.DEFAULT_ATTEMPTS,
                GitInfrastructureRetry.DEFAULT_INITIAL_BACKOFF)
    }
}
