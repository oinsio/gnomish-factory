package com.github.oinsio.gnomish.adapter.check.github

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache
import com.github.oinsio.gnomish.adapter.github.GithubFastRetryConfig
import com.github.oinsio.gnomish.logtext.RepeatSuppressor
import com.github.oinsio.gnomish.testfixtures.time.MovableClock
import java.time.Duration
import java.time.Instant

/**
 * Builds the {@link GithubWorkflowRunPoll} that {@code GithubWorkflowRunPollWireMockSpec} and
 * {@code GithubWorkflowFailureFindingsWireMockSpec} drive: the real query, jobs fetcher and
 * conditional-request cache over {@code GithubFastRetryConfig}'s rate-limit-aware fast policy —
 * which both need, since the poll spec asserts the 429 path — with the repeat suppressor's clock
 * frozen at {@link Instant#EPOCH} so no spec depends on wall-clock time.
 *
 * <p>Shared because those two specs previously hand-duplicated the identical assembly.
 * {@code GithubTokenHygieneSpec} still builds its own: it needs a distinct token value, which
 * this fixture does not expose.
 */
class GithubWorkflowPollFixture {

    private static final Duration ROLL_UP = Duration.ofMinutes(5)

    static GithubWorkflowRunPoll pollFor(String baseUrl) {
        def cache = new GithubConditionalRequestCache(GithubFastRetryConfig.fastClient(baseUrl))
        new GithubWorkflowRunPoll(
                new GithubWorkflowRunQuery(cache, 'acme', 'widgets'),
                new GithubWorkflowJobsFetcher(cache, 'acme', 'widgets'),
                new RepeatSuppressor(new MovableClock(Instant.EPOCH), ROLL_UP))
    }
}
