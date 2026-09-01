package com.github.oinsio.gnomish.logtext;

import java.time.Duration;

/**
 * What a {@link RepeatSuppressor} says about one reported failure — the edge, not the state. The
 * caller maps the three forms onto levels per {@code docs/adr/0004-logging-policy.md}: {@link
 * First} and {@link RollUp} at the site's own level, {@link Repeat} at DEBUG.
 *
 * <p>The suppressor decides <em>which</em> form; it never logs and never picks a level. That split
 * is what lets one owner serve a WARN-level poll loop and a DEBUG-level reconciliation loop
 * without knowing anything about either.
 *
 * <p>Implements FR4 of harden-logging-observability.
 */
public sealed interface RepeatOccurrence {

    /** The failure reason as the caller phrased it — carried back so the line reads from one place. */
    String reason();

    /**
     * The streak's first failure, or its first under a changed reason: a new fact the operator has
     * not been told yet. Logged at the site's level.
     *
     * @param reason the reason the caller reported; never null
     */
    record First(String reason) implements RepeatOccurrence {}

    /**
     * A repetition of a reason already announced, before the next roll-up is due. Logged at DEBUG —
     * it carries no new information, only the fact that the condition persists.
     *
     * @param reason the reason the caller reported; never null
     * @param count how many failures this streak has seen, this one included; at least 2
     */
    record Repeat(String reason, long count) implements RepeatOccurrence {}

    /**
     * The periodic reminder that the condition is still there, carrying the count so the operator
     * can tell a stuck dependency from a flapping one. Logged at the site's level.
     *
     * @param reason the reason the caller reported; never null
     * @param count how many failures this streak has seen, this one included; at least 2
     * @param elapsed how long the streak has lasted, from its first failure; never null
     */
    record RollUp(String reason, long count, Duration elapsed) implements RepeatOccurrence {}
}
