package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * Design D5 of fix-terminal-receipt-convergence, the {@code BranchShape.forwardedByBranchPass()}
 * row: which shapes the janitor's branch pass hands to the claimless cleanup owner is one table, on
 * the shape itself in {@code :domain}, beside {@code recoveryOwner()} and {@code disposition()}
 * (design D13). The pass reads that table and decides nothing of its own. The old way — the pass's
 * own {@code instanceof BranchShape.CompletedUncleaned} — forwarded a branch the clone held without
 * its register to no one, a duty that died with a green build; a second hand-written test over the
 * shape would grow the same gap the next time a shape is added (M8, implementation.md item 4).
 *
 * <p>So {@code instanceof BranchShape.} does not appear in {@code serve/DeliveredBranchPass.java},
 * and the file is asserted reached, so a moved file fails instead of passing over nothing. Its own
 * spec rather than a feature of {@link ReceiptProbeGateSpec}, which is past the file-size cap. A text
 * scan with comments stripped, for the reason {@link BaseHeadDefaultBoundarySpec} gives: the pass's
 * javadoc may name the test it no longer writes. A test of the location ({@code instanceof
 * BranchLocation.}) is not a shape decision and stays allowed.
 *
 * <p>M8, FR11 of fix-terminal-receipt-convergence.
 */
class BranchPassTableGateSpec extends Specification {

    private static final String PASS =
    'application/src/main/java/com/github/oinsio/gnomish/app/serve/DeliveredBranchPass.java'

    /** A type test against any {@code BranchShape} member, qualified, whatever the spacing. */
    static final Pattern SHAPE_TEST = ~/instanceof\s+BranchShape\s*\./

    // M8, FR11, design D5, D13: the branch pass forwards by the shape's own table, never by a type test
    def "M8: the branch pass holds no type test of a BranchShape, and the scan reaches it"() {
        given: 'the branch pass, found by the production source scan'
        def pass = RepoSourceTree.productionSources { it == PASS }

        expect: 'the scan reached the pass — a moved file must fail, not pass'
        pass.collect { RepoSourceTree.relative(it) } == [PASS]

        and: 'its code, comments stripped, tests no shape: forwarding is forwardedByBranchPass()'
        !SHAPE_TEST.matcher(RepoSourceTree.code(pass.first())).find()
    }

    // The detector is the gate: every spelling of the old type test must be found, and the
    //     table read, a location test and a javadoc mention must not be.
    def "the shape-test detector: #shape"() {
        expect:
        SHAPE_TEST.matcher(RepoSourceTree.stripComments(source)).find() == detected

        where:
        shape | source || detected
        'the deleted forwarding test' | 'if (row.shape() instanceof BranchShape.CompletedUncleaned) {' || true
        'a pattern-matching test' | 'if (shape instanceof BranchShape.Unregistered u) {' || true
        'spaced around the dot' | 'boolean b = s instanceof BranchShape . Delivered;' || true
        'a negated test' | 'if (!(shape instanceof BranchShape.Bare)) {' || true
        'the table read' | 'if (!row.shape().forwardedByBranchPass()) {' || false
        'a location test' | 'location instanceof BranchLocation.Local' || false
        'a javadoc mention' | '/** no longer tests instanceof BranchShape.CompletedUncleaned */\nint x;' || false
    }
}
