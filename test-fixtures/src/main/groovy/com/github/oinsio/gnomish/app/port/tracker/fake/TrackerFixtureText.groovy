package com.github.oinsio.gnomish.app.port.tracker.fake

import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * Carrier helpers for the fixtures that stand in for a tracker (design D3 of type-untrusted-text:
 * "fakes in {@code :test-fixtures} and the in-memory tracker mint {@code TRACKER} for fixture
 * titles — a fixture is a tracker").
 *
 * <p>They exist so a spec that only wants "a task called X" says so in one call rather than
 * spelling the mint at every construction, and so the provenance a fixture uses is decided in one
 * place: a fixture that mints something else would compare unequal to the adapter's own value for
 * reasons that have nothing to do with the behaviour under test.
 *
 * <p>Implements FR1, FR4 of type-untrusted-text.
 */
final class TrackerFixtureText {

    private TrackerFixtureText() {}

    /** The tracker's own text, as the adapters mint it. */
    static UntrustedText tracker(String text) {
        UntrustedText.tracker(text)
    }

    /** A snapshot whose title and body are tracker text. */
    static TaskSnapshot snapshot(String id, String title, String body) {
        new TaskSnapshot(id, tracker(title), tracker(body))
    }

    /** A task context whose title and body are tracker text, with no decisions. */
    static TaskContext context(String taskId, String title, String body) {
        context(taskId, title, body, [])
    }

    /** A task context whose title and body are tracker text. */
    static TaskContext context(String taskId, String title, String body, List<Decision> decisions) {
        new TaskContext(taskId, tracker(title), tracker(body), decisions)
    }
}
