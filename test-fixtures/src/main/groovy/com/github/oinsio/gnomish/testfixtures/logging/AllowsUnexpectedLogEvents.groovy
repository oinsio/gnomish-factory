package com.github.oinsio.gnomish.testfixtures.logging

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

/**
 * Declares that a spec (or one feature of it) may traverse a production WARN/ERROR path without
 * pinning the line it emits, so the runtime log-expectation gate ({@link LogExpectationGate},
 * FR17 of harden-logging-observability) lets its events pass.
 *
 * <p>The normal way to declare an expectation is to attach {@link LogCaptureSupport} — an
 * asserted event is by construction an expected one. This annotation is the escape hatch for the
 * rare feature whose subject is something else entirely and whose path happens to cross a degrade
 * line: an end-to-end run over a deliberately broken collaborator, a kill-point harness that
 * provokes a whole family of recovery WARNs.
 *
 * <p>{@code reason} has no default on purpose — the same shape {@code real-time-wiring} and
 * {@code log-contract-exempt} use: the escape hatch is allowed, an undocumented escape hatch is
 * not. A blank reason fails the feature in both gate modes.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.TYPE, ElementType.METHOD])
@interface AllowsUnexpectedLogEvents {

    /** Why this spec cannot pin the operator lines its run emits. */
    String reason()
}
