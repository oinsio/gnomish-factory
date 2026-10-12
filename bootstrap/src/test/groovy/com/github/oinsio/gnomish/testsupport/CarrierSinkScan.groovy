package com.github.oinsio.gnomish.testsupport

import java.util.regex.Pattern

/**
 * Rule (c) of the untrusted-text gate as one scan (FR7, design D2 of type-untrusted-text): every
 * sink call in a source that takes an untrusted-text carrier as itself, rather than through an
 * exit, and every log call fed by the findings funnel's sanitizer — the wrong exit for a log
 * line. The four sink kinds are an SLF4J call, a {@code Throwable} constructor, {@code
 * ConsoleIO.print*} and the text-carrying {@code Tracker} writes.
 *
 * <p>Owned here rather than by one spec because more than one spec asks the same question of it:
 * {@code UntrustedTextSinkGateSpec} runs it over the whole production tree and over its seeded
 * shapes, and {@code LocalStringBypassGateSpec} over the local-string bypass shapes. A second copy
 * of the detector would be a second gate that could disagree with the first.
 */
final class CarrierSinkScan {

    /** A throwable's own constructor: the message it is built with is a sink like any other. */
    static final Pattern THROWABLE = Pattern.compile('new\\s+\\w*(?:Exception|Error|Throwable)\\s*\\(')

    /** The operator's terminal: {@code ConsoleIO.print} and {@code printMachine}. */
    static final Pattern CONSOLE = Pattern.compile('\\.print\\w*\\s*\\(')

    /**
     * The tracker port's text-carrying writes.
     *
     * <p>Kept in sync with {@link com.github.oinsio.gnomish.architecture.TrackerPublicationOwnerSpec} ({@code TRACKER_WRITE}): both declare the same
     * regex over the tracker port's text-carrying write method names.
     */
    static final Pattern TRACKER =
    Pattern.compile('\\.(?:park|finish|declineFinished|acknowledgeDecision|postNote)\\s*\\(')

    /**
     * The findings funnel's sanitizer called at a log site: text prepared for a finding, not for
     * a log line, so the call took the wrong exit (F98; {@code logging.md}: the findings facade is
     * not for log lines). The tracker plane's twin is {@code TrackerPublicationOwnerSpec}'s
     * {@code WRONG_EXIT}, which bans the same facade at a tracker write.
     */
    static final Pattern FINDINGS_EXIT = Pattern.compile('\\bFindingsSanitizer\\s*\\.\\s*\\w+\\s*\\(')

    /**
     * Floors on each sink shape, so a broken pattern reports the same empty violation list as a
     * clean tree. Measured on the tree this gate landed on: 615 throwable constructions, 46
     * console prints, 39 tracker writes.
     */
    static final Map<Pattern, Integer> SINKS = [
        (THROWABLE): 400,
        (CONSOLE): 30,
        (TRACKER): 25,
    ]

    private CarrierSinkScan() {}

    /**
     * Every sink call in one already comment-stripped source that takes a carrier as itself, as
     * {@code path:line}.
     *
     * @param accessors the bare accessor names that yield a carrier
     * @param typedThrowables throwables whose constructor declares its detail as untrusted text —
     *     there the carrier at the call is the exit contract, not the violation
     * @param qualified owner-qualified accessor names, decided where the receiver is declared
     */
    static List<String> violations(
            String code,
            String path,
            Set<String> accessors,
            Set<String> typedThrowables = [] as Set,
            Set<String> qualified = [] as Set) {
        def declared = CarrierArguments.declaredNames(code)
        def receivers = CarrierArguments.declaredTypes(code)
        def logged = LogCallSites.inSource(code, path).findAll { call ->
            JavaCallSites.chainArguments(call.text).any {
                FINDINGS_EXIT.matcher(it).find()
                || CarrierArguments.isCarrier(it, accessors, declared, qualified, receivers)
            }
        }
        def written = SINKS.keySet().collectMany {
            JavaCallSites.callsOf(code, path, it)
        }.findAll { call ->
            !declaresCarrierDetail(call.text, typedThrowables) && call.arguments.any {
                CarrierArguments.isCarrier(it, accessors, declared, qualified, receivers)
            }
        }
        (logged + written).collect {
            "${it.path}:${it.line}".toString()
        }.unique().sort()
    }

    /** Whether this call constructs a throwable that declares its detail as untrusted text. */
    private static boolean declaresCarrierDetail(String call, Set<String> typedThrowables) {
        def construction = THROWABLE.matcher(call)
        construction.find() && typedThrowables.contains(construction.group().replaceFirst('^new\\s+', '').trim()[0..-2].trim())
    }
}
