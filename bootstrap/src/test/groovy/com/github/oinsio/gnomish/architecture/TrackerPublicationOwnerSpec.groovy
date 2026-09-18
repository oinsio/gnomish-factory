package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.CarrierAccessors
import com.github.oinsio.gnomish.testsupport.CarrierArguments
import com.github.oinsio.gnomish.testsupport.JavaCallSites
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import java.util.regex.Pattern
import spock.lang.Shared
import spock.lang.Specification

/**
 * FR8, design D7 of type-untrusted-text: the comment exit owns tracker publication. Every
 * component that writes prose to the tracker — a park report, a finish summary, an abort marker's
 * cause, a decision acknowledgement, a stop note — publishes text whose untrusted parts left their
 * carrier through {@code UntrustedText.forComment()}, and through nothing else.
 *
 * <p>Two questions, one scan, because each alone would pass on a tree that fails the other:
 *
 * <ul>
 *   <li><b>No raw carrier at the write.</b> Rule (c) of {@link UntrustedTextSinkGateSpec} owns the
 *       same question for four sink kinds at once; this spec asks it again over the tracker writes
 *       alone, because the rest of the rule below is about <em>which</em> exit, and a gate that
 *       could not see the raw form would be judging only half the shapes.
 *   <li><b>Not the wrong exit.</b> A write prepared by {@code LogText} or by
 *       {@code FindingsSanitizer} is the failure this rule exists for: both produce inert text, so
 *       nothing is red, and both are wrong here — {@code LogText} flattens a report to one capped
 *       line, and {@code FindingsSanitizer} is a different control at a different boundary
 *       ({@code .claude/rules/logging.md}). Neither fences, neither labels, and neither breaks a
 *       mention, so a tracker comment prepared by either still pings {@code @team} and still lets
 *       an injected line read as an instruction to the next model on the thread.
 * </ul>
 *
 * <p>What it deliberately does not ask is whether {@code forComment()} appears at the write
 * itself. Design D7 splits the writers in two: the <em>callers</em> of the exit render what they
 * hold ({@code FinishEffect}, {@code DecisionAck}, {@code GithubStateWrites}, the two stop notes),
 * while the eight park writers are <em>consumers of its value</em> — they receive finished report
 * text from a builder and must not render it a second time. A gate demanding the call at every
 * write would fail exactly the writers that are correct.
 *
 * <p>Lives in {@code :bootstrap}: a whole-tree source gate, in the module whose {@code test} task
 * wires {@code repoRoot}.
 */
class TrackerPublicationOwnerSpec extends Specification {

    /**
     * The tracker port's text-carrying writes; {@code heartbeat} carries a machine document.
     *
     * <p>Kept in sync with {@link UntrustedTextSinkGateSpec}: both declare the same regex over
     * the tracker port's text-carrying write method names.
     */
    private static final Pattern TRACKER_WRITE =
    Pattern.compile('\\.(?:park|finish|declineFinished|acknowledgeDecision|postNote)\\s*\\(')

    /**
     * The abort marker's construction. Scanned for the wrong exit but <em>not</em> for a raw
     * carrier: since task 6.3 its {@code cause} is declared {@code UntrustedText}, so a carrier
     * there is the contract being honoured rather than a laundering — the adapter that publishes
     * the marker is the one that renders it. Handing it {@code LogText}-flattened text is still a
     * violation, and the shape this half of the scan exists to catch.
     */
    private static final Pattern ABORT_RECORD = Pattern.compile('new\\s+AbortRecord\\s*\\(')

    /**
     * A floor on each shape, so a broken pattern reports the same empty violation list a clean
     * tree does. Measured on the tree this gate landed on: 39 tracker writes, 20 abort records
     * (production and fixture contract suites together).
     */
    private static final Map<Pattern, Integer> WRITES = [
        (TRACKER_WRITE): 25,
        (ABORT_RECORD): 1,
    ]

    /** The shapes whose own parameter type is a {@code String}, so a carrier there is laundering. */
    private static final Set<Pattern> STRING_TYPED = [TRACKER_WRITE] as Set

    /** The two facades that prepare text for another plane entirely. */
    private static final Pattern WRONG_EXIT = Pattern.compile('\\b(?:LogText|FindingsSanitizer)\\s*\\.\\s*\\w+\\s*\\(')

    @Shared
    JavaClasses productionClasses = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .importPackages('com.github.oinsio.gnomish')

    @Shared
    Set<String> carrierAccessors = CarrierAccessors.namesIn(productionClasses)

    // FR8: the tracker plane has one exit, and a write that took another one is inert but wrong.
    def "FR8: every tracker publication renders through the comment exit and nothing else"() {
        given: 'every production source, with its comments removed'
        def sources = RepoSourceTree.productionSources()

        expect: 'the scan really reached the source tree'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        and: 'and every write shape inside it — a floor on files alone would pass on an empty set'
        WRITES.every { shape, floor ->
            JavaCallSites.productionCallsOf(shape).size() >= floor
        }

        when:
        def offenders = sources.collectMany { file ->
            violations(RepoSourceTree.code(file), RepoSourceTree.relative(file), carrierAccessors)
        }

        then: 'the gate names every offending write'
        offenders == []
    }

    // D7: the detector is the gate — a green scan by a detector that sees nothing looks identical.
    def "FR8: a seeded #shape is detected"() {
        expect:
        violations("UntrustedText report = capture();\n${code}", 'Seeded.java', ['stderr'] as Set) ==
        ['Seeded.java:2']

        where:
        shape | code
        'raw park' | 'tracker.park(ref, ParkReason.INFRA, report);'
        'raw postNote' | 'tracker.postNote(ref, report);'
        'log-exit park' | 'tracker.park(ref, ParkReason.INFRA, LogText.forLog(text));'
        'findings-funnel postNote' | 'tracker.postNote(ref, FindingsSanitizer.forLog(text));'
        'log-exit abort record' | 'new AbortRecord(LogText.forLog(text), instance, at);'
    }

    // D7: and the shapes that are correct — a gate flagging these would be uninstalled the day the
    //     eight park writers, which receive finished builder text, were all reported as offenders.
    def "FR8: a correct write is not flagged: #shape"() {
        expect:
        violations("UntrustedText report = capture();\n${code}", 'Ok.java', ['stderr'] as Set) == []

        where:
        shape | code
        'the caller of the exit' | 'tracker.finish(ref, summary.forComment());'
        'a consumer of the builder value' | 'tracker.park(ref, ParkReason.INFRA, fullReport);'
        'an accessor through the exit' | 'tracker.postNote(ref, result.stderr().forComment());'
        'a factory-authored constant' | 'tracker.declineFinished(ref, "not finished on the branch");'
        'the carrier the marker declares' | 'new AbortRecord(report, instance, at, category);'
    }

    /**
     * Every offending write in one source, as {@code path:line}: an argument that is a carrier as
     * written, or one prepared by a facade that serves another plane.
     */
    private static List<String> violations(String code, String path, Set<String> accessors) {
        def declared = CarrierArguments.declaredNames(code)
        WRITES.keySet()
                .collectMany { shape ->
                    JavaCallSites.callsOf(code, path, shape).findAll { call ->
                        call.arguments.any { argument ->
                            WRONG_EXIT.matcher(argument).find()
                            || (shape in STRING_TYPED
                            && CarrierArguments.isCarrier(argument, accessors, declared))
                        }
                    }
                }
                .collect { "${it.path}:${it.line}".toString() }
                .unique()
                .sort()
    }
}
