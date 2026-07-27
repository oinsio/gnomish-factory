package com.github.oinsio.gnomish.app.port.tracker;

import java.time.Instant;

/**
 * A single human reply comment collected by {@code collectDecisions}: free-text
 * {@code body} posted at {@code postedAt}, in posting order relative to other
 * replies (tracker-port spec, "Decision collection anchored to the last ack").
 *
 * <p>Named {@code HumanReply} rather than {@code Decision} deliberately: {@link
 * com.github.oinsio.gnomish.domain.engine.Decision} already exists as the
 * engine-facing value the runner appends via {@code TaskRepository} and carries
 * into a resumed run. This type is the adapter-facing raw material collected
 * from the tracker at resume claim — before it is turned into an engine {@code
 * Decision} — so it is a different value at a different layer, and reusing the
 * name would collide two related but distinct concepts (FR12).
 *
 * <p>Inert value data compared by content; a blank {@code body} is rejected
 * because a reply with no message carries nothing to act on.
 *
 * <p>Implements FR12 of add-tracker-port.
 *
 * @param body the free-text reply, carried verbatim; never blank
 * @param postedAt when the reply was posted, used to order replies and to
 *     anchor collection to the last ack; never null
 */
public record HumanReply(String body, Instant postedAt) {

    public HumanReply {
        body = requireNonBlank(body);
    }

    /**
     * Fails fast on a blank {@code body}: a reply with no message is meaningless
     * context (FR12). Kept as an explicit static method rather than inline in the
     * compact constructor: PIT's record filter suppresses all mutations inside a
     * record's canonical constructor, which would silently exempt this validation
     * from the 100% mutation gate.
     */
    private static String requireNonBlank(String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("HumanReply.body must not be blank");
        }
        return value;
    }
}
