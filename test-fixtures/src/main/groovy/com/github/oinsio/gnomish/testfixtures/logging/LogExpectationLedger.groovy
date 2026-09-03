package com.github.oinsio.gnomish.testfixtures.logging

import ch.qos.logback.classic.spi.ILoggingEvent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.regex.Pattern

/**
 * What the whole run of one test task saw on the operator plane, accumulated across features so
 * the end-of-run verdict can be asked by operator-event code rather than by event instance.
 *
 * <p>Why by code (FR17, the hybrid rollout of task 14.2): judged per event instance, every
 * behavior spec that merely crosses an already-pinned degrade path is an offender — the September
 * 2026 observing run named 162 specs and 667 features that way, almost all of them for lines some
 * sibling feature pins properly. The defect the requirement actually names is "a new degrade path
 * enters the codebase with its line unasserted", and that is a statement about a code, not about
 * one traversal of it. So the failure is: a code this run emitted, that no capture anywhere in the
 * run was watching. The per-feature detail is not discarded — it is written out beside the
 * verdict, because a line emitted in silence is exactly what this change exists to end.
 *
 * <p>State is per JVM, which is per test task: the accumulation is only valid within one run, and
 * the file it writes is that run's evidence. {@code LogExpectationGateCheck} in {@code
 * build-logic} reads the files back and renders the verdict.
 */
final class LogExpectationLedger {

    /** The catalog head every operator line carries — {@code OperatorEvent} in {@code :logtext}. */
    private static final Pattern CODE = Pattern.compile('\\[(GF\\d{3})]')

    /** Written for a line whose site is a documented {@code log-contract-exempt}, so it has no code. */
    private static final String NO_CODE = '-'

    private static final Set<String> WATCHED = ConcurrentHashMap.newKeySet()

    private static final List<String> ROWS = new CopyOnWriteArrayList<String>()

    private LogExpectationLedger() {
    }

    /** Records one finished feature's operator plane. */
    static void record(String location, OperatorLogEvents events, boolean allowed) {
        events.watched.each { WATCHED.add(codeOf(it)) }
        events.unwatched.each { event ->
            ROWS.add(row(allowed ? 'allowed' : 'unwatched', codeOf(event), location, event))
        }
    }

    /** Everything this run saw, one observation per line, for the build-logic verdict to read. */
    static String observations() {
        (WATCHED.sort().collect { "watched\t$it" } + ROWS).join('\n') + '\n'
    }

    /** Forgets the run — for the gate's own spec, which must not leak into the real verdict. */
    static void reset() {
        WATCHED.clear()
        ROWS.clear()
    }

    private static String codeOf(ILoggingEvent event) {
        def matcher = CODE.matcher(event.formattedMessage ?: '')
        matcher.find() ? matcher.group(1) : NO_CODE
    }

    private static String row(String kind, String code, String location, ILoggingEvent event) {
        [
            kind,
            code,
            location,
            event.level.toString(),
            event.loggerName,
            flatten(event.formattedMessage)
        ].join('\t')
    }

    /** One observation is one line: the message's own newlines and tabs must not forge a second. */
    private static String flatten(String message) {
        (message ?: '').replaceAll('[\\t\\r\\n]', ' ')
    }
}
