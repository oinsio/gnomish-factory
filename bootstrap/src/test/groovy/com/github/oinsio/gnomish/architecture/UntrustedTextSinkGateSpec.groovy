package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.CarrierAccessors
import com.github.oinsio.gnomish.testsupport.CarrierArguments
import com.github.oinsio.gnomish.testsupport.CarrierConstructors
import com.github.oinsio.gnomish.testsupport.JavaCallSites
import com.github.oinsio.gnomish.testsupport.LogCallSites
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import java.lang.reflect.Modifier
import java.util.regex.Pattern
import spock.lang.Shared
import spock.lang.Specification

/**
 * Rule (c) of the untrusted-text gate (FR7, design D2 of type-untrusted-text): a sink never takes
 * an untrusted-text carrier as a direct argument — the value passes an exit first, so the intent
 * is visible where the text is written rather than three calls away.
 *
 * <p>Four sink kinds, each a place text leaves the factory for a reader who cannot defend
 * themselves: an SLF4J call (a log record other people read as evidence), a {@code Throwable}
 * constructor (a message that will be logged, stored and echoed), {@code ConsoleIO.print*} (an
 * operator's terminal), and the text-carrying {@code Tracker} writes — {@code park}, {@code
 * finish}, {@code declineFinished}, {@code acknowledgeDecision}, {@code postNote}.
 * {@code heartbeat}'s payload is a machine document, not prose, and is deliberately outside the
 * list.
 *
 * <p>The {@code AbortRecord} constructor left that list at task 6.3, when its {@code cause} became
 * an {@link com.github.oinsio.gnomish.untrustedtext.UntrustedText}. It is no longer a sink but a
 * port type, and the carrier at its call is the correct form rather than the violation — the same
 * reasoning {@link com.github.oinsio.gnomish.testsupport.CarrierConstructors} applies to a
 * throwable that declares its detail as untrusted text. Where the marker's text actually leaves
 * for a reader is the adapter that publishes it, and that is
 * {@link TrackerPublicationOwnerSpec}'s question (FR8, design D7).
 *
 * <p>A source scan rather than an ArchUnit rule, in the retired accessor gate's shape, because
 * ArchUnit sees call targets and parameter types but never the static type of an argument
 * expression. What it keys on has changed though, and that is the point of the migration: the
 * retired gate matched a hand-kept list of accessor <em>names</em>, which had to be edited in the
 * change that introduced the next untrusted accessor. This one asks the bytecode which accessors
 * yield a carrier ({@link CarrierAccessors}), so a family typed in a later task joins the scan by
 * itself. Until a family is typed its accessors are plain strings and this rule cannot see them —
 * which is why the accessor-name gate stayed alive, narrowed family by family, until its last name
 * landed (task 2.5). It was not deleted with that name: what it could see and no type rule can is
 * text the factory never wrapped, so task 7.4 retargeted it at the point of capture as
 * {@link RawCaptureGateSpec} (FR11, design D12).
 *
 * <p>Lives in {@code :bootstrap}: it is a whole-tree source gate, and this is the module whose
 * {@code test} task wires {@code repoRoot}.
 */
class UntrustedTextSinkGateSpec extends Specification {

    /** A throwable's own constructor: the message it is built with is a sink like any other. */
    private static final Pattern THROWABLE = Pattern.compile('new\\s+\\w*(?:Exception|Error|Throwable)\\s*\\(')

    /** The operator's terminal: {@code ConsoleIO.print} and {@code printMachine}. */
    private static final Pattern CONSOLE = Pattern.compile('\\.print\\w*\\s*\\(')

    /**
     * The tracker port's text-carrying writes.
     *
     * <p>Kept in sync with {@link TrackerPublicationOwnerSpec}: both declare the same regex over
     * the tracker port's text-carrying write method names.
     */
    private static final Pattern TRACKER =
    Pattern.compile('\\.(?:park|finish|declineFinished|acknowledgeDecision|postNote)\\s*\\(')

    /**
     * Floors on each sink shape, so a broken pattern reports the same empty violation list as a
     * clean tree. Measured on the tree this gate landed on: 615 throwable constructions, 46
     * console prints, 39 tracker writes.
     */
    private static final Map<Pattern, Integer> SINKS = [
        (THROWABLE): 400,
        (CONSOLE): 30,
        (TRACKER): 25,
    ]

    @Shared
    JavaClasses productionClasses = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .importPackages('com.github.oinsio.gnomish')

    @Shared
    Set<String> carrierAccessors = CarrierAccessors.namesIn(productionClasses)

    /**
     * The same accessors as owner-qualified pairs, which is how the scan reaches the names the
     * bare-name rule must stay silent about — {@code stderr}, {@code stdout} and {@code output}
     * among them, i.e. the whole git/docker/in-box capture vocabulary (design D2; the silence
     * itself is pinned below).
     */
    @Shared
    Set<String> qualifiedAccessors = CarrierAccessors.qualifiedNamesIn(productionClasses)

    /**
     * Throwables whose constructor declares the detail as untrusted text (design D5): there the
     * parameter type is the exit contract, so the carrier at the call is the correct form rather
     * than the violation — see {@link CarrierConstructors}.
     */
    @Shared
    Set<String> carrierThrowables = CarrierConstructors.throwableTypeNamesIn(productionClasses)

    // FR7: a carrier handed straight to a sink is the laundering the type exists to make visible.
    def "FR7: rule (c): no sink takes a carrier without an exit"() {
        given: 'every production source, with its comments removed'
        def sources = RepoSourceTree.productionSources()

        expect: 'the scan really reached the source tree'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        and: 'and the log calls inside it — a floor on files alone would pass on an empty call set'
        LogCallSites.productionCalls().size() >= LogCallSites.KNOWN_LOG_CALLS

        and: 'and delimited every one of them — a site the parser drops is a site nobody judges'
        LogCallSites.unparsedProductionCalls() == []

        and: 'and the qualified vocabulary really reaches the names the bare-name rule cannot'
        qualifiedAccessors.containsAll([
            'GitCommandResult.stderr',
            'GitCommandResult.stdout',
            'DockerResult.stderr',
            'DockerResult.stdout',
            'Outcome.output',
        ])

        and: 'and reached every other sink shape too'
        SINKS.every { shape, floor ->
            JavaCallSites.productionCallsOf(shape).size() >= floor
        }

        when: 'each source is scanned for a carrier passed to a sink as itself'
        def offenders = sources.collectMany { file ->
            violations(
            RepoSourceTree.code(file),
            RepoSourceTree.relative(file),
            carrierAccessors,
            carrierThrowables,
            qualifiedAccessors)
        }

        then: 'the gate names every offending site'
        offenders == []
    }

    // D2: the detector is the gate — a green scan over a tree with no carriers in it yet would
    //     look identical to a green scan by a detector that cannot see one.
    def "FR7: a seeded carrier at a #sink is detected"() {
        expect: 'the site is named — the carrier arrives as an accessor result or as a local'
        violations("UntrustedText detail = capture();\n${code}", 'Seeded.java', ['stderr'] as Set) ==
        ['Seeded.java:2']

        where:
        sink | code
        'log call' | 'log.warn("stage command failed: {}", result.stderr());'
        'throwable constructor' | 'throw new GitPersistFailedException(result.stderr());'
        'console print' | 'console.print(detail);'
        'console machine print' | 'console.printMachine(detail);'
        'tracker park' | 'tracker.park(ref, reason, detail);'
        'tracker finish' | 'tracker.finish(ref, detail);'
        'tracker declineFinished' | 'tracker.declineFinished(ref, detail);'
        'tracker acknowledgeDecision' | 'tracker.acknowledgeDecision(ref, detail);'
        'tracker postNote' | 'tracker.postNote(ref, detail);'
        'mint' | 'log.warn("malformed: {}", UntrustedText.container(e.getOriginalMessage()));'
    }

    // D2, M1: the two shapes the retired gate learned to see — carried over so no detector shape
    //     is lost when it goes. The fluent builder carries its arguments past the level call.
    def "FR7: a seeded carrier in the fluent form is detected: #call"() {
        expect:
        violations("UntrustedText held = capture();\n${call}", 'SeededFluent.java', ['stderr', 'stdout'] as Set).size() == 1

        where:
        call << [
            'log.atLevel(levelOf(v)).log("swept {}: {}", v, result.stderr());',
            'log.atWarn().setMessage("boom").addArgument(result.stdout()).log();',
            'log.atWarn().setMessage("boom").addArgument(held).log();'
        ]
    }

    // D2: and the forms that are correct — an exit at the argument, an exit further out, and the
    //     concatenation design D1 makes safe. A gate that flagged these would be uninstalled.
    def "FR7: the exit form is not flagged: #call"() {
        expect:
        violations("UntrustedText held = capture();\n${call}", 'Correct.java', ['stderr', 'stdout'] as Set) == []

        where:
        call << [
            'log.warn("push failed: {}", result.stderr().forLog());',
            'log.warn("push failed: {}", result.stderr().excerpt(CAP));',
            'log.debug("event {}", String.valueOf(started.sessionId()));',
            'log.atLevel(levelOf(v)).log("swept {}: {}", v, result.stderr().forLog());',
            'throw new GitPersistFailedException("could not persist: " + result.stderr());',
            'console.print(held.forConsole());',
            'tracker.park(ref, reason, held.forComment());',
            'tracker.postNote(ref, "aborted: " + held);'
        ]
    }

    // D2, G1: the ambiguity rule alone left rule (c) silent over `stderr`/`stdout`/`output` — the
    //     entire git/docker/in-box capture vocabulary, i.e. the families the rule was written for.
    //     Where the receiver was declared in the same source, the owner decides what the bare name
    //     could not, so the seeded call below is named although its accessor is ambiguous.
    def "FR7: an ambiguous accessor on a declared carrier receiver is detected: #sink"() {
        expect:
        violations(
                "GitCommandResult result = runner.run(dir, \"commit\");\n${code}",
                'Declared.java',
                [] as Set,
                [] as Set,
                [
                    'GitCommandResult.stderr',
                    'GitCommandResult.stdout'
                ] as Set) == ['Declared.java:2']

        where:
        sink | code
        'log call' | 'log.warn("stage command failed: {}", result.stderr());'
        'console print' | 'console.print(result.stdout());'
        'tracker postNote' | 'tracker.postNote(ref, result.stderr());'
    }

    // D2: and the other half of the same evidence — the owner that answers to the ambiguous name
    //     with a plain String is now decided too, rather than merely un-scanned. A rule that
    //     flagged this would fail the build on a suppressor's own prose.
    def "FR7: the same accessor on a declared String receiver is not flagged"() {
        expect:
        violations(
                "DrainReport report = drain.await();\nlog.info(\"drain finished: {}\", report.summary());",
                'StringOwner.java',
                [] as Set,
                [] as Set,
                ['StatusReport.summary'] as Set) == []
    }

    // D2: a name declared twice under two types in one source is no evidence either — the scan
    //     reads no scopes, so it must not pick one of the two declarations and act on it.
    def "FR7: a receiver name declared under two types decides nothing"() {
        expect:
        violations(
                'GitCommandResult result = runner.run(dir, "commit");\n'
                + 'DrainReport result = drain.await();\n'
                + 'log.info("finished: {}", result.stderr());',
                'TwoDeclarations.java',
                [] as Set,
                [] as Set,
                ['GitCommandResult.stderr'] as Set) == []
    }

    // D2: what the scan cannot decide, pinned so it cannot quietly grow. A name both a carrier
    //     and a plain String answer to is no evidence either way, so it is out of the scan — and
    //     it re-enters by itself when the family wearing the String twin is typed.
    def "FR7: the names the scan must stay silent about are exactly these"() {
        // Each name is a carrier on one owner and a plain String on another, so the scan can
        // conclude nothing from the name alone. `stderr`/`stdout` because `:gitobjects`' own exec
        // result is still a String; the other six because a wire DTO, a check-configuration
        // failure or a serve report answers to the same name with a plain String — the JSON
        // records behind `EscalationReportDto`/`EscalationDto`, the `reason()` of the http and
        // files-exist check errors, `TrackedTask.summary()`, `DrainReport.summary()`. Task 5.0
        // typed the three ports cut A rendered across (`ExecutionResult`, `EscalationReport`,
        // `PollStatus`), which is why none of the six names its own carrier owner here any more.
        // Task 5.1 added the tracker family's `title`, `body` and `humanText` — carriers on the
        // snapshot, the context and the parsed marker, plain Strings on the wire DTOs they are
        // written into and on an HTTP response's own body. Task 5.2 added the manifest family's
        // `label`, the two `Activity` names `prompt` and `currentTool`, `staleStage`, and
        // `render` — a carrier on `ConfigError` since D10, a plain renderer on the briefing, the
        // status renderer, the repair log and the batch summary.
        // Task 5.3 added the branch-document family's `cause` — a carrier on every outcome and
        // report that names a failure, a plain String on the `task.json` / `status.json` wire
        // records those are written into.
        // Task 6.1 added `report` and `value`: `report` is a carrier on the two base-ref refusal
        // arms and a plain String on the serve/branch reports that name no capture, and `value` is
        // a carrier on `BaseDesignator.Single` and a plain String on the port's own
        // `Designator.Single`, on `InstanceId` and on every other identity that answers to it —
        // which is exactly D11's "make it parsed, do not type it" boundary seen from the scan's
        // side. `cause` stayed listed for one more reason until 6.3 and now has one fewer: the
        // `AbortRecord` twin became a carrier there.
        // Each re-enters the scan by itself once its last String twin is typed.
        // What this list no longer means is "rule (c) is silent here": since the qualified
        // vocabulary landed, a name on it is still decided wherever the receiver's type is
        // declared in the same source (`CarrierAccessors.qualifiedNamesIn`, the three features
        // above). The list is what is left when the source says nothing about the receiver — a
        // `var`, a chained call — and there the bare name really is no evidence either way.
        expect:
        CarrierAccessors.ambiguousNamesIn(productionClasses).sort() == [
            'body',
            'cause',
            'currentTool',
            'details',
            'humanText',
            'label',
            'model',
            'note',
            'output',
            'prompt',
            'question',
            'reason',
            'render',
            'report',
            'result',
            'staleStage',
            'stderr',
            'stdout',
            'summary',
            'title',
            'value'
        ]
    }

    // D2: the derivation the whole scan rests on — asked of a subject that really has a carrier
    //     accessor, so an empty answer over a tree that has none yet cannot hide a broken one.
    def "FR7: the carrier accessors are derived from the return types"() {
        given: 'the seeded capture record, whose accessors are a carrier, a list of them, and an int'
        def seeded = new ClassFileImporter().importPackages('com.github.oinsio.gnomish.architecture.seeded')

        expect:
        CarrierAccessors.namesIn(seeded).containsAll(['stderr', 'options'])

        and:
        !CarrierAccessors.namesIn(seeded).contains('exitCode')
    }

    // D3: the mint list is the one part of the scan that is still a hand-kept set of names, so it
    //     is pinned against the leaf itself — a family added there without a line here would be a
    //     mint the sink gate cannot see, which is exactly the hole rule (c) exists to close.
    def "FR7: the scanned mints are exactly the leaf's own"() {
        given: 'every static factory of the carrier that takes text and returns one'
        def mints = UntrustedText.declaredMethods
                .findAll {
                    Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) &&
                    it.returnType == UntrustedText && it.parameterTypes == [String] as Class[]
                }
                .collect { "UntrustedText.${it.name}" as String }
                .toSorted()

        expect: 'the leaf really has mints, so an empty answer cannot pass this'
        mints.size() >= 7

        and: 'and the scan knows every one of them'
        CarrierArguments.MINTS.toSorted() == mints
    }

    /** Whether this call constructs a throwable that declares its detail as untrusted text. */
    private static boolean declaresCarrierDetail(String call, Set<String> typedThrowables) {
        def construction = THROWABLE.matcher(call)
        construction.find() && typedThrowables.contains(construction.group().replaceFirst('^new\\s+', '').trim()[0..-2].trim())
    }

    /** Every sink call in one already comment-stripped source that takes a carrier as itself. */
    private static List<String> violations(
            String code,
            String path,
            Set<String> accessors,
            Set<String> typedThrowables = [] as Set,
            Set<String> qualified = [] as Set) {
        def declared = CarrierArguments.declaredNames(code)
        def receivers = CarrierArguments.declaredTypes(code)
        def logged = LogCallSites.inSource(code, path).findAll { call ->
            JavaCallSites.chainArguments(call.text).any {
                CarrierArguments.isCarrier(it, accessors, declared, qualified, receivers)
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
}
