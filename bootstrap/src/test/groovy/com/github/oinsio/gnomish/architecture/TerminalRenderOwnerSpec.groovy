package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * Design D7 of make-run-headless, first single-owner row: {@code TerminalOutcomeRender} is the one
 * render of a stopped task — the checkpoint sentence and the return-path line naming the resume
 * command. Before it, the checkpoint sentence was spelled by hand in five production files, among
 * them {@code take}'s pause park and outcome mapper; a sixth copy would compile, read correctly in
 * its own spec, and drift from the rest on the next wording change. Every consumer takes the value
 * from the owner, so the literals have exactly one home, and this scan keeps it that way.
 *
 * <p>The return-path literal scanned is {@code To continue: gnomish run} — the head of the owner's
 * {@code RETURN_PATH_PREFIX} without its trailing {@code --dir=}. A hand copy of the line has to
 * spell that sentence and command whatever it does with the flags after it; the full prefix would
 * let a copy that drops or reorders {@code --dir} pass.
 *
 * <p>A whole-tree text scan in {@code :bootstrap}, the one module that sees every layer, for the
 * reason {@link BaseHeadDefaultBoundarySpec} gives: a string literal is no dependency a type rule
 * can see. The shared fixtures are scanned too — they hold no copy, and a fixture that spelled one
 * would let a fake drift from the render it stands in for. Comments are stripped: the owner's own
 * javadoc and the classes that cite it may name the sentence.
 *
 * <p>FR1, FR2, FR8 of make-run-headless.
 */
class TerminalRenderOwnerSpec extends Specification {

    private static final String OWNER = 'application/src/main/java/com/github/oinsio/gnomish/app/TerminalOutcomeRender.java'

    /** The literals only the owner may spell, each with what it renders. */
    private static final Map<String, String> OWNED_LITERALS = [
        'passed. Awaiting approval.': 'the checkpoint sentence (run stop render and take park reports)',
        'To continue: gnomish run' : 'the return-path line naming the resume command',
    ]

    // FR1, FR2, FR8: each literal has exactly one production home, the owner — take included
    def "FR1, FR2, FR8: '#literal' is spelled in production only by TerminalOutcomeRender"() {
        given: 'every production source of the build, comments removed'
        def sources = RepoSourceTree.productionSources()

        expect: 'the scan really reached the tree — a mis-resolved root would pass silently'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        and: 'the owner is in the scanned set, so a rename cannot leave the gate naming nothing'
        sources.collect { RepoSourceTree.relative(it) }.contains(OWNER)

        when:
        def spellers = sources.findAll {
            RepoSourceTree.code(it).contains(literal)
        }
        .collect { RepoSourceTree.relative(it) }
        .sort()

        then: 'the owner, and only the owner — an extra file is a hand copy, none a moved render'
        spellers == [OWNER]

        where:
        literal << OWNED_LITERALS.keySet().toList()
    }

    // The detector is the gate — over a clean tree it finds only the owner, so a seeded copy must be
    //     found and a comment that names the sentence must not be
    def "the detector: #shape"() {
        expect:
        OWNED_LITERALS.keySet().any {
            RepoSourceTree.codeOnly(line).contains(it)
        } == detected

        where:
        shape | line || detected
        'a hand-spelled checkpoint' | 'return "Stage \'" + s + "\' passed. Awaiting approval.";' || true
        'a hand-spelled return path' | 'console.print("To continue: gnomish run --resume " + id);' || true
        'a javadoc mention' | ' * @return {@code Stage \'<s>\' passed. Awaiting approval.}' || false
        'a trailing comment' | 'var line = TerminalOutcomeRender.checkpointLine(s); // Awaiting approval' || false
    }
}
