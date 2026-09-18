package com.github.oinsio.gnomish.architecture

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

import com.github.oinsio.gnomish.testsupport.CarrierAccessors
import com.github.oinsio.gnomish.testsupport.ParserPassThrough
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import com.github.oinsio.gnomish.untrustedtext.UntrustedExit
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import spock.lang.Shared
import spock.lang.Specification

/**
 * The type half of the untrusted-text gate (FR3, FR7, design D2 of type-untrusted-text), in the
 * two rules ArchUnit can decide from bytecode:
 *
 * <ul>
 *   <li><b>Rule (a)</b> — {@link UntrustedText#raw()} is read only inside a class marked
 *       {@link UntrustedExit}. Raw access is the one way text leaves the carrier without passing
 *       an exit, so the allowlist is the annotation, and the annotated set itself is pinned below:
 *       a class that joins it fails this spec until the growth is acknowledged.
 *   <li><b>Rule (a2)</b> — {@link UntrustedText#forParsing()} is read only inside a class marked
 *       {@link UntrustedParser}. Machine-readable capture is read for the opposite reason prose is
 *       — to become a value, not to be shown — so it leaves through its own way out with its own
 *       allowlist, rather than widening rule (a)'s seven classes to thirty-three and leaving them
 *       meaning nothing (design D11). The pinned set is a map, class to what it converts the text
 *       into, so the warrant is read beside the name; and the one mechanical half of the
 *       membership criterion — a "parser" that hands the text straight back — is checked on the
 *       source by {@link ParserPassThrough}.
 *   <li><b>Rule (b)</b> — every accessor in the capture vocabulary returns the carrier rather than
 *       a {@code String}. A family whose accessor still yields a plain string has no mint, and
 *       every rule downstream of the type is vacuous for it.
 * </ul>
 *
 * <p>The third rule — no carrier passed straight into a sink — cannot be decided here: ArchUnit
 * sees call targets and parameter types, never the static type of an argument expression. It is a
 * source scan, in {@link UntrustedTextSinkGateSpec}.
 *
 * <p>Rule (b)'s vocabulary is declared <b>per capture family</b> (design D9), so the rule runs
 * over exactly the families the current cut migrates and is never red over one it does not. The
 * table below holds cut A's families — git, docker, in-box exec and agent, plus the one domain
 * carrier the agent adapter constructs — and, since task 5.0, cut B's: tracker, manifest label,
 * branch document, and the report and status text they reach. A private Jackson wire record
 * ({@code DecisionFileReader.Payload}) is deliberately absent: it is the shape the JSON has, not
 * an accessor the factory reads text through — the mint sits in the reader that builds
 * {@code Decision} from it.
 *
 * <p>Lives in {@code :bootstrap} for the same reason as {@link AtomicWriteBoundarySpec}: this is
 * the module whose test classpath sees every layer at once.
 */
class UntrustedTextGateSpec extends Specification {

    /** Seeded subjects live here; the production import never reaches them. */
    private static final String SEEDED_PACKAGE = 'com.github.oinsio.gnomish.architecture.seeded'

    /** The seeded pass-through parser's own source, read by the scan that must catch it. */
    private static final String PASS_THROUGH_SEED = 'bootstrap/src/test/groovy/com/github/oinsio/gnomish/' +
    'architecture/seeded/PassThroughParserSeed.java'

    /**
     * Every production class allowed to read the raw text, by design D2: the carrier itself, the
     * machine writers whose media carry raw bytes bounded by their own encoding — the two branch
     * documents, the ledger and snapshot, and the two {@code --json} mappers that write a check's
     * own words into a parser's input (found at task 3.3, when the verdict became a carrier; its
     * {@code status.json} siblings {@code EscalationMapper} and {@code StatusReportJsonMapper}
     * joined them at tasks 5.0 and 5.1, when the escalation report and the task title became
     * carriers, and {@code BoardJsonMapper} with them — {@code board --json} is the same machine
     * plane) —
     * and the two funnel entries that build a {@code Finding}. {@code TrackerFence} is deliberately not here —
     * a {@code String → String} facade over the comment exit never reads {@code raw()}, so
     * annotating it would widen the allowlist for nothing.
     */
    private static final List<String> ANNOTATED_EXITS = [
        'com.github.oinsio.gnomish.adapter.agent.JudgeVerdictExtractor',
        'com.github.oinsio.gnomish.adapter.check.github.GithubWorkflowJobsFetcher',
        'com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper',
        'com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper',
        'com.github.oinsio.gnomish.board.json.BoardJsonMapper',
        'com.github.oinsio.gnomish.serveobservability.json.LedgerJsonMapper',
        'com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonMapper',
        'com.github.oinsio.gnomish.status.json.AttemptMapper',
        'com.github.oinsio.gnomish.status.json.EscalationMapper',
        'com.github.oinsio.gnomish.status.json.StatusReportJsonMapper',
        'com.github.oinsio.gnomish.untrustedtext.UntrustedText',
        'com.github.oinsio.gnomish.usage.json.UsageReportJsonMapper',
    ]

    /**
     * Every production class allowed to read the captured bytes for parsing, and — the reason it
     * may — what it turns them into (design D11). A map rather than a list so the warrant is read
     * beside the name: a class joining the set fails this spec until the growth is acknowledged
     * <em>with its conversion</em>. Cut A's git family fills it at task 3.1, the docker and in-box
     * families at 3.2, and the agent family's one parser at 3.3.
     *
     * <p>Two entries differ from design D11's list, both found by writing the code:
     * {@code WorktreeSalvage} is absent — its only captured read is the dirty-tree question, which
     * the carrier's own {@code isBlank()} answers with no text leaving it — and
     * {@code ContainerHarvestFetch} is present, because it classifies a fetch's <em>stderr</em>
     * case-insensitively into a failure class, which D11's stdout-derived list did not consider.
     *
     * <p>A third joined at task 6.1, when the designator chain was typed: {@code
     * BaseDesignator$Single} matches a tracker's base label against the allowed bases, and the
     * match is {@code RefNameSyntax}-gated by {@code BasePattern.matches} — the named-syntax-gate
     * warrant of D11, applied in the module whose whole point is that it can reach nothing.
     */
    private static final Map<String, String> PARSER_CONVERSIONS = [
        'com.github.oinsio.gnomish.adapter.git.ContainerHarvestFetch':
        'a failed harvest fetch\'s stderr into the failure class it belongs to',
        'com.github.oinsio.gnomish.adapter.git.FactoryCloneHardening':
        'rev-parse --absolute-git-dir output into the clone\'s git directory Path',
        'com.github.oinsio.gnomish.adapter.git.GitProcessRunner':
        'rev-parse --git-common-dir output into the Path the mutation lock keys on',
        'com.github.oinsio.gnomish.adapter.git.HarvestedBoundaryCheck':
        'a name-only diff into the list of state-directory paths the gnome touched',
        'com.github.oinsio.gnomish.adapter.git.LocalBranchTip':
        'rev-parse --verify output into the branch\'s commit id',
        'com.github.oinsio.gnomish.adapter.git.OriginRemote':
        'remote get-url output into the origin URL, or empty',
        'com.github.oinsio.gnomish.adapter.git.RemoteBaseRef':
        'ls-remote lines into the commit id each named ref points at',
        'com.github.oinsio.gnomish.adapter.git.RemoteBranchTip':
        'an ls-remote line into origin\'s tip commit id for the branch',
        'com.github.oinsio.gnomish.adapter.git.RemoteDefaultBranch':
        'an ls-remote --symref answer into the default branch name, gated by DefaultBranch\'s rules',
        'com.github.oinsio.gnomish.adapter.git.ReplicaPairReconciler':
        'rev-parse output into a ref\'s commit id, or null',
        'com.github.oinsio.gnomish.adapter.git.RoundBoundaryCheck':
        'symbolic-ref and name-only diff output into the two boundary verdicts (booleans)',
        'com.github.oinsio.gnomish.adapter.git.SnapshotTipCheck':
        'a NUL-separated log line into the snapshot\'s stage and round',
        'com.github.oinsio.gnomish.adapter.git.TaskBranchLister':
        'for-each-ref output into the list of task-branch refs, each held to the factory\'s own prefix',
        'com.github.oinsio.gnomish.adapter.git.TaskWorktreeManager':
        'worktree list --porcelain output into whether this worktree is registered (a boolean)',
        'com.github.oinsio.gnomish.adapter.git.UsageHistoryWalker':
        'a --format=%H log into the list of state-touching commit ids',
        'com.github.oinsio.gnomish.adapter.git.VerifiedTip':
        'rev-parse output into the commit id a resolution established, or empty',
        'com.github.oinsio.gnomish.sandbox.environment.ContainerMaterializer':
        'a container inspect answer into whether the surviving container is running (a boolean)',
        'com.github.oinsio.gnomish.sandbox.environment.DeclaredVolumeOverrides':
        'an image inspect answer into the declared volume paths, which travel back to docker as'
        + ' mount destinations and are never rendered to a reader',
        'com.github.oinsio.gnomish.sandbox.environment.EgressGuard':
        'a guard inspect answer into whether the guard container is running (a boolean)',
        'com.github.oinsio.gnomish.sandbox.environment.EgressSelfCheckProbes':
        'an in-box probe\'s output into whether the guard answered its own 403 (a boolean)',
        'com.github.oinsio.gnomish.sandbox.environment.EnvironmentSelfCheck':
        'the in-box uid and two inspect answers into the booleans the self-check decides on',
        'com.github.oinsio.gnomish.sandbox.environment.GuardDenialReads':
        'the guard log into denial findings, a saturation boolean and an RFC-3339 read position',
        'com.github.oinsio.gnomish.sandbox.environment.GuardSourceIdentity':
        'a guard inspect answer into the denial source id, held to ContainerIdSyntax',
        'com.github.oinsio.gnomish.sandbox.environment.OomAnnotatedExecHandle':
        'a container state line into whether the cgroup OOM killer fired (a boolean)',
        'com.github.oinsio.gnomish.sandbox.environment.SandboxLifecycleObjectReader':
        'docker listings and inspect lines into listed objects with their labels, and into the'
        + ' timing Instants the sweep decides on',
        'com.github.oinsio.gnomish.adapter.agent.TokenUsageMapper':
        'the round\'s model id into a telemetry key, held to ModelIdSyntax',
        'com.github.oinsio.gnomish.baseref.BaseDesignator$Single':
        'a task\'s base designator label into the accepted ref name, held to RefNameSyntax by'
        + ' BasePattern.matches — a value no pattern accepts is converted into nothing, since the'
        + ' refusal carries the carrier itself',
    ]

    /**
     * The capture vocabulary: the accessor names of each family, by their owning record. Cut A's
     * families come first (git, docker, in-box exec, agent, and the one domain carrier the agent
     * adapter constructs), then cut B's, added at task 5.0: tracker, manifest label, branch
     * document and the report/status text those two families reach.
     *
     * <p>Two names design D9 lists for cut B have no owner here, and both absences are the
     * table's own rule — a vocabulary entry names a class that exists and a method it declares:
     * {@code message} belongs to {@code ConfigError}, which stays a {@code String} record whose
     * {@code render()} is the manifest mint (design D10), and {@code humanText}'s owner
     * {@code ParsedMarker} is listed below with only {@code humanText}: its {@code instance} is an
     * identity compared against this process's own configured id and against a claim holder, so
     * task 5.1 held it to {@code InstanceIdSyntax} instead, the third time design D11's
     * "make it parsed, do not type it" answer applied. The two
     * {@code DecisionNeeded} variants are listed although D9's cut B sentence does not repeat
     * {@code question}/{@code options}: task 5.0 types both ports when it deletes the cut
     * boundary's {@code forConsole()} renderings (design D6), and an accessor typed with no rule
     * over it is exactly the vacuous family rule (b) exists to prevent.
     */
    private static final Map<String, List<String>> CAPTURE_VOCABULARY = [
        'com.github.oinsio.gnomish.adapter.git.GitCommandResult': ['stdout', 'stderr'],
        'com.github.oinsio.gnomish.adapter.git.InBoxGitCommand$Outcome': ['output'],
        'com.github.oinsio.gnomish.sandbox.environment.DockerResult': ['stdout', 'stderr'],
        'com.github.oinsio.gnomish.sandbox.environment.EgressSelfCheckProbes$Probe': ['output'],
        'com.github.oinsio.gnomish.sandbox.CapturedExec': ['output'],
        'com.github.oinsio.gnomish.adapter.agent.AgentEvent$InitEvent': ['sessionId', 'model'],
        'com.github.oinsio.gnomish.adapter.agent.AgentEvent$AssistantEvent': ['sessionId', 'model'],
        'com.github.oinsio.gnomish.adapter.agent.AgentEvent$UserEvent': ['sessionId'],
        'com.github.oinsio.gnomish.adapter.agent.AgentEvent$ResultEvent': ['sessionId'],
        'com.github.oinsio.gnomish.adapter.agent.DecisionFileReader$Decision': ['question', 'options'],
        'com.github.oinsio.gnomish.domain.engine.Verdict$CannotVerify': ['reason', 'details'],
        'com.github.oinsio.gnomish.domain.engine.ExecutionResult$DecisionNeeded': ['question', 'options'],
        'com.github.oinsio.gnomish.domain.engine.EscalationReport$DecisionNeeded': ['question', 'options'],
        'com.github.oinsio.gnomish.domain.engine.EscalationReport$CannotVerify': ['reason', 'details'],
        'com.github.oinsio.gnomish.domain.engine.EscalationReport$CannotExecute': ['cause'],
        'com.github.oinsio.gnomish.domain.engine.EscalationReport$PipelineMismatch': ['staleStage'],
        'com.github.oinsio.gnomish.domain.engine.PollStatus$CannotVerify': ['reason', 'details'],
        'com.github.oinsio.gnomish.domain.engine.TaskOutcome$Aborted': ['cause'],
        'com.github.oinsio.gnomish.domain.engine.TaskContext': ['title', 'body'],
        'com.github.oinsio.gnomish.domain.engine.CheckRef': ['label'],
        'com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot': ['title', 'body'],
        'com.github.oinsio.gnomish.app.port.tracker.AbortRecord': ['cause'],
        'com.github.oinsio.gnomish.adapter.tracker.github.ParsedMarker': ['humanText'],
        'com.github.oinsio.gnomish.status.StatusReport': ['title', 'body'],
        'com.github.oinsio.gnomish.status.Activity$AwaitingInput': ['prompt'],
        'com.github.oinsio.gnomish.status.Activity$Executing': ['currentTool'],
    ]

    @Shared
    JavaClasses productionClasses = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .importPackages('com.github.oinsio.gnomish')

    @Shared
    JavaClasses seededClasses = new ClassFileImporter().importPackages(SEEDED_PACKAGE)

    // FR3: raw text leaves the carrier in one place only — otherwise the exits are advice.
    def "FR3: rule (a): only an annotated exit owner reads the raw text"() {
        expect: 'no other production class calls it'
        rawAccessRule().check(productionClasses)
    }

    // D2: the detector is the gate — a rule no seeded violation can fail says nothing when green.
    def "FR3: rule (a) fails on a seeded unannotated caller and spares the annotated one"() {
        when: 'the rule runs over the seeded pair'
        rawAccessRule().check(seededClasses)

        then: 'it names the unannotated reader, and only it'
        def failure = thrown(AssertionError)
        failure.message.contains('RawReadingSeed')
        !failure.message.contains('AnnotatedExitSeed')
    }

    // D2, risk "a machine writer could be misused as a laundering hatch": the allowlist is the
    //     annotation, so the only thing that keeps it honest is noticing when it grows.
    def "FR3: the annotated exit owners are exactly the pinned set"() {
        expect: 'every annotated production class is one design D2 names'
        productionClasses.findAll { it.isAnnotatedWith(UntrustedExit) }
        .collect { it.fullName }
        .sort() == ANNOTATED_EXITS
    }

    // FR10: the bytes a parse needs are the bytes as captured, so the parsing exit hands them over
    //     uncapped — which makes "who may call it" exactly as much of a question as raw() is.
    def "FR10: rule (a2): only an annotated parser reads the captured bytes"() {
        expect: 'no other production class calls it'
        parsingAccessRule().check(productionClasses)
    }

    // D11: the detector is the gate — the same seeded pair rule (a) carries, for the other way out.
    def "FR10: rule (a2) fails on a seeded undeclared reader and spares the annotated one"() {
        when: 'the rule runs over the seeded pair'
        parsingAccessRule().check(seededClasses)

        then: 'it names the class that reads the bytes without declaring itself a parser'
        def failure = thrown(AssertionError)
        failure.message.contains('ForParsingSeed')
        !failure.message.contains('AnnotatedParserSeed')
    }

    // D11, risk "@UntrustedParser becomes the laundering hatch @UntrustedExit was kept from being":
    //     26 classes may read the bytes, so the set is only reviewable if it cannot grow quietly.
    def "FR10: the annotated parsers are exactly the pinned set, each with what it converts to"() {
        expect: 'every annotated production class is one design D11 names'
        productionClasses.findAll { it.isAnnotatedWith(UntrustedParser) }
        .collect { it.fullName }
        .sort() == PARSER_CONVERSIONS.keySet().sort()

        and: 'and the warrant beside each name says something'
        PARSER_CONVERSIONS.every { _, conversion -> !conversion.isBlank() }
    }

    // D11: membership is by return type — the one half of that criterion a scan can decide is its
    //     exact negation, a "parser" whose method returns the captured text straight back.
    def "FR10: rule (a2): no annotated parser hands the captured text back unchanged"() {
        given: 'every production source, with its comments removed'
        def sources = RepoSourceTree.productionSources()

        expect: 'the scan really reached the source tree'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        when:
        def offenders = sources.collectMany {
            ParserPassThrough.handingTextBack(RepoSourceTree.code(it), RepoSourceTree.relative(it))
        }

        then: 'the gate names every offending method'
        offenders == []
    }

    // D11: and that detector, on the seeded parser written to break it — naming the method, since
    //     the class is a legitimate parser everywhere else the day it gains a second method.
    def "FR10: a seeded parser that returns the captured text is detected, by method"() {
        given: 'the seeded offender, read as the production scan reads a source'
        def seed = RepoSourceTree.repoRoot().resolve(PASS_THROUGH_SEED).toFile()

        when:
        def offenders = ParserPassThrough.handingTextBack(RepoSourceTree.code(seed), 'PassThroughParserSeed.java')

        then: 'the one method that launders is named, and nothing else is'
        offenders.size() == 1
        offenders[0].startsWith('PassThroughParserSeed.java:')
        offenders[0].endsWith(' launder')
    }

    // D11: and the shapes that really are conversions — a gate flagging these would be uninstalled.
    def "FR10: converting the captured bytes is not flagged: #shape"() {
        expect:
        ParserPassThrough.handingTextBack("String parse(UntrustedText t) {\n${shape}\n}", 'Ok.java') == []

        where:
        shape << [
            'return Optional.of(captured.forParsing());',
            'return RefNameSyntax.of(result.stdout().forParsing());',
            'return UntrustedText.branchDocument(captured.forParsing());',
            'return captured.forLog();'
        ]
    }

    // FR7: an accessor that still yields a String is a family with no mint — every gate downstream
    //     of the type is vacuous for it, and the family's text reaches sinks as it always did.
    def "FR7: rule (b): the #accessor accessor of #owner returns the carrier"() {
        given: 'the record the family is captured into'
        def carrier = productionClasses.find { it.fullName == owner }

        expect: 'the vocabulary names a class that still exists'
        carrier != null

        and: 'and a method it still declares'
        def method = carrier.methods.find {
            it.name == accessor && it.rawParameterTypes.isEmpty()
        }
        method != null

        and: 'which yields untrusted text, not a plain string'
        CarrierAccessors.carrierTyped(method)

        where:
        [owner, accessor] << CAPTURE_VOCABULARY.collectMany { name, accessors ->
            accessors.collect { [name, it] }
        }
    }

    // D2: rule (b)'s own detector, on a subject shaped the way the rule wants and one shaped the
    //     way it forbids — a predicate that answered true for everything would look identical.
    def "FR7: rule (b) accepts a carrier and a list of carriers, and nothing else"() {
        given:
        def seed = seededClasses.find {
            it.fullName == "${SEEDED_PACKAGE}.CarrierAccessorSeed"
        }

        expect:
        CarrierAccessors.carrierTyped(accessorOf(seed, 'stderr'))
        CarrierAccessors.carrierTyped(accessorOf(seed, 'options'))
        !CarrierAccessors.carrierTyped(accessorOf(seed, 'exitCode'))
    }

    /** Rule (a) as an ArchUnit rule, built per check so the two subjects share no state. */
    private static def rawAccessRule() {
        def rawRead = new DescribedPredicate<JavaMethodCall>('a read of UntrustedText.raw()') {
                    @Override
                    boolean test(JavaMethodCall call) {
                        call.target.owner.fullName == UntrustedText.name && call.target.name == 'raw'
                    }
                }
        noClasses().that().areNotAnnotatedWith(UntrustedExit).should().callMethodWhere(rawRead)
    }

    /** Rule (a2) as an ArchUnit rule, built per check so the two subjects share no state. */
    private static def parsingAccessRule() {
        def parsingRead = new DescribedPredicate<JavaMethodCall>('a read of UntrustedText.forParsing()') {
                    @Override
                    boolean test(JavaMethodCall call) {
                        call.target.owner.fullName == UntrustedText.name && call.target.name == 'forParsing'
                    }
                }
        noClasses().that().areNotAnnotatedWith(UntrustedParser).should().callMethodWhere(parsingRead)
    }

    /** The no-argument method of that name, which the caller has already asserted exists. */
    private static JavaMethod accessorOf(JavaClass owner, String name) {
        owner.methods.find { it.name == name && it.rawParameterTypes.isEmpty() }
    }
}
