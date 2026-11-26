package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * Design D22 of migrate-verify-to-executor-contract, the single-owner row "The
 * interrupted-verification resume": the one owner of "no salvage over an interrupted verification"
 * is {@code NoSalvageOverVerification}. Every path that settles an interrupted round's leftovers —
 * both media's resume preparation and both revocation stops — reaches the salvage and the discard
 * through it, so none can salvage over a registered snapshot or pending commit again; the in-box
 * reset over a pending round (D11 of extract-agent-round-runner, "Resume") is reached the same way. Before it,
 * {@code GitResumeContinuation}, {@code TakeResumeRunner}, {@code RevocationHandler} and {@code
 * ContainerRunSupport.revocationSalvageAndPush} each salvaged unconditionally.
 *
 * <p>The scan covers the two layers that hold the callers, {@code application/src/main} and {@code
 * bootstrap/src/main}, comments stripped. Each allowlisted file must still make the call, so a moved
 * call fails instead of widening the gate.
 *
 * <p>FR19 of migrate-verify-to-executor-contract.
 */
class NoSalvageOwnerSpec extends Specification {

    private static final String OWNER = 'application/src/main/java/com/github/oinsio/gnomish/app/NoSalvageOverVerification.java'

    private static final String BOX = 'bootstrap/src/main/java/com/github/oinsio/gnomish/app/ContainerRunSupport.java'

    /** Per call shape, the files allowed to make it, each with why. */
    private static final Map<String, Map<String, String>> ALLOWED = [
        salvage: [
            (OWNER): 'the owner: the worktree salvage handle, then its salvage commit',
            (BOX): 'salvageLeftovers itself, the in-box mechanism the owner calls',
        ],
        discard: [
            (OWNER): 'the owner: the host discard over an interrupted verification or under --discard-work',
            (BOX): 'discardLeftovers itself, the in-box reset the owner calls over a pending round',
        ],
        salvageLeftovers: [
            (OWNER): 'the owner: the in-box salvage when the register names no interrupted verification',
        ],
        discardLeftovers: [
            (OWNER): 'the owner: the in-box reset over a pending round, so its resumed poll runs at the register (D11 of extract-agent-round-runner)',
        ],
    ]

    private static final Map<String, String> SHAPES = [
        salvage: /\.\s*salvage\s*\(/,
        discard: /\.\s*discard\s*\(\s*\)/,
        salvageLeftovers: /\.\s*salvageLeftovers\s*\(/,
        discardLeftovers: /\.\s*discardLeftovers\s*\(/,
    ]

    // FR19: the leftovers of an interrupted round are settled through the one owner only
    def "FR19: a #shape call in application or bootstrap appears only in the allowlisted files"() {
        given: 'every production source of the two layers, comments stripped'
        def sources = RepoSourceTree.productionSources { String path ->
            path.startsWith('application/src/main/') || path.startsWith('bootstrap/src/main/')
        }

        expect: 'the scan really reached both layers'
        sources.any { RepoSourceTree.relative(it).startsWith('application/') }
        sources.any { RepoSourceTree.relative(it).startsWith('bootstrap/') }

        when:
        def callers = sources.findAll {
            RepoSourceTree.code(it) =~ SHAPES[shape]
        }
        .collect { RepoSourceTree.relative(it) }
        .sort()

        then: 'each allowlisted file is reached; no other file makes the call'
        callers == ALLOWED[shape].keySet().sort()

        where:
        shape << [
            'salvage',
            'discard',
            'salvageLeftovers',
            'discardLeftovers'
        ]
    }

    // The detector is the gate — a seeded call must be found, a comment or a declaration must not
    def "the detector: #description"() {
        expect:
        (RepoSourceTree.codeOnly(line) =~ SHAPES[shape]).find() == detected

        where:
        description | shape | line || detected
        'a host salvage' | 'salvage' | 'salvager.salvage(taskId);' || true
        'a chained salvage' | 'salvage' | 'git.worktrees().salvage(worktree).salvage(id);' || true
        'a salvage declaration' | 'salvage' | 'public void salvage(String taskId) {' || false
        'a commented salvage' | 'salvage' | 'run(); // was salvager.salvage(id)' || false
        'a host discard' | 'discard' | 'salvage.discard();' || true
        'a discard of something else' | 'discard' | 'round.discardLater();' || false
        'an in-box salvage' | 'salvageLeftovers' | 'support.salvageLeftovers(taskId);' || true
        'its declaration' | 'salvageLeftovers' | 'void salvageLeftovers(String taskId);' || false
        'an in-box reset' | 'discardLeftovers' | 'support.discardLeftovers();' || true
        'its declaration' | 'discardLeftovers' | 'public void discardLeftovers() {' || false
    }
}
