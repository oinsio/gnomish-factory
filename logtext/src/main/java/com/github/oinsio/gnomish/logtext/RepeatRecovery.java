package com.github.oinsio.gnomish.logtext;

import java.time.Duration;

/**
 * The end of a failure streak, returned by {@link RepeatSuppressor#recovered(String)} when the
 * subject had actually been failing. The counterpart of {@link RepeatOccurrence.First}: an
 * operator told about a fault must also be told when it cleared, or the console's last word on the
 * subject stays wrong forever.
 *
 * <p>Logged at INFO — a recovery is a state change, not a degradation.
 *
 * <p>Implements FR4 of harden-logging-observability.
 *
 * @param reason the last reason the streak reported, so the recovery line names what recovered;
 *     never null
 * @param occurrences how many failures the streak accumulated before it cleared; at least 1
 * @param outage how long the streak lasted, from its first failure to this recovery; never null
 */
public record RepeatRecovery(String reason, long occurrences, Duration outage) {}
