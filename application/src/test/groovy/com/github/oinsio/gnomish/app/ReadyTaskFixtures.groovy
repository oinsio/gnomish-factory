package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * Shared Spock fixture for fresh and returned, non-backed-off {@code ReadyTask} instances used
 * across the feed/take-pipeline specs, varying only the {@code TaskRef} id under test. Extracted
 * because an identical private {@code fresh(String id)} helper was hand-duplicated across
 * {@code take.OpenFrontGateSpec}, {@code take.FeedPolicySpec}, {@code serve.FeedAutomatonSpec}
 * and {@code serve.FeedAutomatonViewSpec}, and an identical private {@code returned(String id)}
 * helper was hand-duplicated across {@code take.OpenFrontGateSpec} and {@code take.FeedPolicySpec}.
 */
class ReadyTaskFixtures {

    static ReadyTask fresh(String id) {
        new ReadyTask(new TaskRef(id), AbortFacts.none(), false, false, UntrustedText.tracker('fixture title'))
    }

    static ReadyTask returned(String id) {
        new ReadyTask(new TaskRef(id), AbortFacts.none(), true, false, UntrustedText.tracker('fixture title'))
    }
}
