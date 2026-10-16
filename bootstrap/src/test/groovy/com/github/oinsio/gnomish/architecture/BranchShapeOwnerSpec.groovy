package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * FR1, FR2 of harden-task-branch-contract: the classifier is the single entry to branch state, and
 * the per-shape handling is exhaustive by construction.
 *
 * <p>Both properties compile in any shape and pass every component spec, which is why they are a
 * whole-tree scan here, in the one module that sees every layer (the placement {@link
 * PositionConstructionGateSpec} uses):
 *
 * <ul>
 *   <li><b>One entry.</b> A {@link BranchShape} value is constructed in production only by the
 *       classifier and by the branch facade that answers from the branch's location before any tip
 *       exists to classify ({@code Bare} for no branch, {@code Corrupt} for one git refused); the
 *       classifier itself is built only by the two tip readers. A reader that decided a shape from
 *       a file-presence check of its own would have to construct the shape, and fails here.
 *   <li><b>No silent default.</b> {@code BranchShape} is sealed over the twelve names, and no
 *       production switch over it carries a {@code default} arm, so a twelfth shape fails to
 *       compile in every handler until each names it.
 * </ul>
 *
 * <p>Comments are stripped ({@link RepoSourceTree#code}); every allowlisted file is asserted
 * reached, so a moved owner fails instead of widening the gate.
 */
class BranchShapeOwnerSpec extends Specification {

    private static final String GIT = 'adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/'
    private static final String APP = 'application/src/main/java/com/github/oinsio/gnomish/app/'
    private static final String DOMAIN = 'domain/src/main/java/com/github/oinsio/gnomish/domain/branch/'

    /** The files that construct a shape value, each with why. */
    private static final Map<String, String> SHAPE_CONSTRUCTORS = [
        (DOMAIN + 'BranchShapeClassifier.java'): 'the classifier: every shape a tip\'s facts decide',
        (GIT + 'GitTaskBranches.java'): 'the location answer: Bare for no branch, Corrupt for a refused one',
    ]

    /** The files that build the classifier, each with why. */
    private static final Map<String, String> CLASSIFIER_BUILDERS = [
        (GIT + 'GitTaskBranches.java'): 'take routing and resume: classifyShape',
        (GIT + 'TipEnvelopeReader.java'): 'status, listing and denial-cursor restore: one read, one classification',
    ]

    /** The per-shape handlers: every production switch over the closed set. */
    private static final List<String> HANDLERS = [
        DOMAIN + 'BranchShape.java',
        APP + 'TakeDispositionResume.java',
        APP + 'branch/BranchShapeDiagnosis.java',
        APP + 'branch/BranchRepairLog.java',
    ]

    private static final String SHAPE_NAMES =
    'Bare|Created|InProgress|AwaitingApproval|Parked|Answered|CompletedUncleaned|Delivered|Unregistered|UnsupportedVersion|Corrupt|Unknown'

    // FR2: a shape comes from the classifier's verdict, never from a reader's own file check
    // Scenario: lifecycle/task-branch-contract / No reader bypasses the classifier
    def "FR2: only the classifier and the location answer construct a shape; only the tip readers build the classifier"() {
        given: 'every production source, comments stripped'
        def sources = RepoSourceTree.productionSources()
        assert sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        expect: 'the shape constructors are exactly the allowlist, each reached'
        filesWhere(sources) {
            constructsShape(it)
        } == SHAPE_CONSTRUCTORS.keySet().sort()

        and: 'the classifier builders are exactly the allowlist, each reached'
        filesWhere(sources) {
            it =~ /new\s+BranchShapeClassifier\s*\(/
        } == CLASSIFIER_BUILDERS.keySet().sort()
    }

    // FR2: the closed set is sealed, and every handler names each shape — no catch-all arm
    // Scenario: lifecycle/task-branch-contract / New shape cannot be silently ignored
    def "FR2: the shape set is sealed over twelve names and no production switch over it has a default arm"() {
        expect: 'sealed: a thirteenth shape must be declared in the one file, and the compiler then sees it'
        BranchShape.isSealed()
        BranchShape.permittedSubclasses*.simpleName.sort() == SHAPE_NAMES.split(/\|/).toList().sort()

        when: 'the production handlers are collected: every file that switches over the shape set'
        def sources = RepoSourceTree.productionSources()
        def handlers = (filesWhere(sources) {
            handlesShapes(it)
        } + [DOMAIN + 'BranchShape.java']).unique()

        then: 'the scan reached every known handler'
        handlers.containsAll(HANDLERS)

        and: 'none of them carries a default arm, so a new shape fails to build in each'
        handlers.findAll {
            hasDefaultArm(RepoSourceTree.code(RepoSourceTree.repoRoot().resolve(it).toFile()))
        } == []
    }

    // The detectors are the gate — a seeded spelling must be found, a mention must not
    def "the detector: #shape"() {
        expect:
        detect(detector, line) == detected

        where:
        shape | detector | line || detected
        'a qualified shape construction' | 'construct' | 'return new BranchShape.Parked();' || true
        'a spaced construction' | 'construct' | 'return new  BranchShape . Corrupt (r);' || true
        'a pattern match' | 'construct' | 'case BranchShape.Parked() -> x;' || false
        'an instanceof' | 'construct' | 'if (s instanceof BranchShape.Bare) {}' || false
        'a qualified case' | 'handle' | 'case BranchShape.Parked() -> x;' || true
        'an unqualified case' | 'handle' | 'case Parked() -> x;' || false
        'a case of another type' | 'handle' | 'case HOST -> x;' || false
        'an arrow default' | 'default' | 'default -> throw new IllegalStateException();' || true
        'a colon default' | 'default' | 'default: return null;' || true
        'an interface default method' | 'default' | 'default RecoveryOwner recoveryOwner() {' || false
    }

    private static List<String> filesWhere(List<File> sources, Closure<Boolean> predicate) {
        sources.findAll {
            predicate(RepoSourceTree.code(it))
        }.collect {
            RepoSourceTree.relative(it)
        }.sort()
    }

    private static boolean constructsShape(String code) {
        code =~ /new\s+BranchShape\s*\.\s*(${SHAPE_NAMES})\s*\(/
    }

    /**
     * A switch arm naming a shape, qualified: {@code BranchShape.java} itself switches unqualified and
     * is added by name, since an unqualified {@code case Parked()} elsewhere may be another type's.
     */
    private static boolean handlesShapes(String code) {
        code =~ /case\s+BranchShape\s*\.\s*(${SHAPE_NAMES})\b/
    }

    private static boolean detect(String detector, String line) {
        switch (detector) {
            case 'construct': return constructsShape(line)
            case 'handle': return handlesShapes(line)
            default: return hasDefaultArm(line)
        }
    }

    private static boolean hasDefaultArm(String code) {
        code =~ /\bdefault\s*(->|:)/
    }
}
