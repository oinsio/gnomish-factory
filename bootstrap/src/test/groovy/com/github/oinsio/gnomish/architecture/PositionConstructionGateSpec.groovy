package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * Design D7 of make-checkpoint-gate-durable: what a pass leaves as position has one owner,
 * {@code Advancement.positionAfter(definition, stage)}, and the gate — {@code
 * Position.AwaitingApproval} — is constructed nowhere else in production. A second construction
 * (a runner parking the task "by hand", an approval that rebuilds the gate it compares against)
 * would compile, pass its own spec, and reopen the defect the durable gate closed: a position
 * decided outside the mode table. The sealed {@code Position} switch makes every reader name the
 * gate; this scan keeps its construction single-owner.
 *
 * <p>The allowlist, by file: the owner ({@code Advancement}, its {@code MANUAL} arm) and the wire
 * reader ({@code StateJsonMapper}, which turns a recorded {@code awaitingApproval} token back into
 * the variant). The repositories' refusal checks pattern-match the variant and never construct
 * it; {@code TaskState.approveGate} constructs the position after the gate through {@code
 * Advancement.afterGate}, never the gate itself. Each allowlisted file must still construct the
 * gate, so a move cannot leave the gate naming nothing.
 *
 * <p>Two spellings are caught. The qualified one — {@code new Position.AwaitingApproval(}, with
 * or without a package prefix — anywhere. The unqualified one — {@code new AwaitingApproval(} —
 * in a file that brings the nested type into scope: an import of {@code
 * ...domain.engine.Position.AwaitingApproval} (plain or static) or of {@code
 * ...domain.engine.Position.*}, or {@code Position.java} itself. Other {@code AwaitingApproval}
 * types ({@code BranchShape}, {@code PositionDto}, {@code StatePositionDto}) are different
 * classes and are not the gate.
 *
 * <p>The whole production tree is scanned at any depth, {@code :test-fixtures} included.
 * Comments are stripped.
 *
 * <p>FR1 of make-checkpoint-gate-durable.
 */
class PositionConstructionGateSpec extends Specification {

    /** The files allowed to construct {@code Position.AwaitingApproval}, each with why. */
    private static final Map<String, String> ALLOWED = [
        ('domain/src/main/java/com/github/oinsio/gnomish/domain/engine/Advancement.java'): 'the owner: positionAfter, MANUAL arm',
        ('adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/state/StateJsonMapper.java'): 'the wire reader of the awaitingApproval token',
    ]

    private static final String POSITION_FQN = 'com\\.github\\.oinsio\\.gnomish\\.domain\\.engine\\.Position'

    // FR1: the gate is constructed only by the owner and the wire reader
    def "FR1: Position.AwaitingApproval is constructed in production only in the allowlisted files"() {
        given: 'every production source, comments stripped'
        def sources = RepoSourceTree.productionSources()

        expect: 'the scan really reached the tree'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        when:
        def constructors = sources.findAll {
            constructsGate(it.name, RepoSourceTree.code(it))
        }
        .collect { RepoSourceTree.relative(it) }
        .sort()

        then: 'each allowlisted file is reached and constructs the gate; no other file does'
        constructors == ALLOWED.keySet().sort()
    }

    // The detector is the gate — a seeded construction must be found, a mention must not
    def "the detector: #shape"() {
        expect:
        constructsGate(fileName, source.readLines().collect {
            RepoSourceTree.codeOnly(it)
        }.join('\n')) == detected

        where:
        shape | fileName | source || detected
        'qualified' | 'X.java' | 'var p = new Position.AwaitingApproval(stage);' || true
        'qualified, spaced' | 'X.java' | 'var p = new  Position . AwaitingApproval (stage);' || true
        'fully qualified' | 'X.java' | "var p = new ${fqn()}.AwaitingApproval(s);" || true
        'imported nested type' | 'X.java' | "import ${fqn()}.AwaitingApproval;\nvar p = new AwaitingApproval(s);" || true
        'static-imported nested type' | 'X.java' | "import static ${fqn()}.AwaitingApproval;\nvar p = new AwaitingApproval(s);" || true
        'wildcard nested import' | 'X.java' | "import ${fqn()}.*;\nvar p = new AwaitingApproval(s);" || true
        'inside Position.java' | 'Position.java' | 'Position gate(String s) { return new AwaitingApproval(s); }' || true
        'a pattern match' | 'X.java' | 'case Position.AwaitingApproval(String gate) -> gate;' || false
        'an instanceof' | 'X.java' | 'if (p instanceof Position.AwaitingApproval) {}' || false
        'a parameter type' | 'X.java' | 'void approve(Position.AwaitingApproval gate) {}' || false
        'another AwaitingApproval type' | 'X.java' | 'return new BranchShape.AwaitingApproval();' || false
        'a dto of the same name' | 'X.java' | 'return new StatePositionDto.AwaitingApproval("awaitingApproval", s);' || false
        'unqualified, type not imported' | 'PositionDto.java' | 'return new AwaitingApproval("awaitingApproval", s);' || false
        'a javadoc mention' | 'X.java' | ' * built as {@code new Position.AwaitingApproval(stage)}' || false
        'a trailing comment' | 'X.java' | 'var p = advance(); // was new Position.AwaitingApproval(s)' || false
    }

    private static String fqn() {
        POSITION_FQN.replace('\\.', '.')
    }

    private static boolean constructsGate(String fileName, String code) {
        if (code =~ /\bnew\s+(?:[\w.]+\.)?Position\s*\.\s*AwaitingApproval\s*\(/) {
            return true
        }
        boolean nestedInScope = fileName == 'Position.java' ||
                code =~ /(?m)^\s*import\s+(?:static\s+)?${POSITION_FQN}\s*\.\s*(?:AwaitingApproval|\*)\s*;/
        nestedInScope && code =~ /(?<![\w.])new\s+AwaitingApproval\s*\(/
    }
}
