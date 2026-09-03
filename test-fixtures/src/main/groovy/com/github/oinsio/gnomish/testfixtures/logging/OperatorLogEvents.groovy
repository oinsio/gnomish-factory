package com.github.oinsio.gnomish.testfixtures.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import groovy.transform.Immutable

/**
 * What one feature put on the operator plane, split by whether a spec's capture was watching.
 *
 * <p>Both halves matter to the hybrid verdict (FR17 of harden-logging-observability): the
 * unwatched half is what the per-feature report names so an operator line is never emitted in
 * silence, and the watched half is what tells the end-of-run verdict that this event's code is
 * asserted somewhere — so a behavior spec crossing an already-pinned degrade path is visible
 * without being a failure.
 */
@Immutable(knownImmutableClasses = [List])
class OperatorLogEvents {

    List<ILoggingEvent> watched

    List<ILoggingEvent> unwatched
}
