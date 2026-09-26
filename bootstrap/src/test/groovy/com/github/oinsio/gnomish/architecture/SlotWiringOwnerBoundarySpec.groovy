package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * FR3 and design D6 of introduce-slot-wiring, single-owner table: three values of the slot
 * wiring are built at named places only, and a construction anywhere else in production is a
 * second owner the parameter types cannot see.
 *
 * <ul>
 *   <li>{@code SlotWiring} — built at the two assembly points, once per {@code take} invocation
 *       and once per {@code serve} daemon, each right after its run's heartbeat and augmented
 *       assembly exist. A third site would assemble the wiring from members that are not the run's.
 *   <li>{@code RemoteOutageSignalingBaseRefGit} — built only by {@code RemoteOutageGates.signaling}.
 *       The record is package-private, but {@code TakeSlotRunner} shares its package, so the
 *       slot re-decorating its own git (the old way D6 removed) would still compile.
 *   <li>{@code ClaimTenure} — built only by {@code TakeHeartbeat.tenure()}, which pairs the beat
 *       with the SAME flag wired as its lost-claim sink; a tenure from any other flag detaches the
 *       round-boundary consult from the beat. Specs may build their own, so only production is
 *       scanned.
 * </ul>
 *
 * <p>Each allowlist is also checked for staleness: every listed file must really construct the
 * value, so a moved assembly point fails here instead of leaving a dead entry behind.
 */
class SlotWiringOwnerBoundarySpec extends Specification {

    private static final String APP = 'application/src/main/java/com/github/oinsio/gnomish/app/'

    /** Constructed type → the production files allowed to construct it. */
    private static final Map<String, List<String>> OWNERS = [
        'SlotWiring': [
            APP + 'TakeCommand.java',
            APP + 'ServeRuntimeAssembly.java',
        ],
        'RemoteOutageSignalingBaseRefGit': [
            APP + 'serve/RemoteOutageGates.java',
        ],
        'ClaimTenure': [
            APP + 'TakeHeartbeat.java',
        ],
    ]

    // FR3, D6: a construction outside the allowlist is a second owner of the value.
    def "FR3, D6: only the named owners construct a #type in production"() {
        given: 'every production source of the build, comments stripped'
        def sources = RepoSourceTree.productionSources()

        expect: 'the scan really reached the tree'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        when:
        def constructing = sources.findAll {
            constructs(type, RepoSourceTree.code(it))
        }
        .collect { RepoSourceTree.relative(it) }
        .sort()

        then: 'nothing outside the allowlist constructs it, and every allowlisted file does'
        constructing == OWNERS[type].sort()

        where:
        type << OWNERS.keySet().toList()
    }

    // Over a clean tree the scan finds nothing but the owners, so only seeded sources tell a
    //     working detector from a broken one; a comment naming the constructor must not count.
    def "the detector finds a seeded construction and leaves a commented one alone: #shape"() {
        expect:
        constructs(type, source) == detected

        where:
        shape | type | source || detected
        'a third assembly point' | 'SlotWiring' | 'var wiring = new SlotWiring(assembly, git, root,' || true
        'a constructor reference' | 'ClaimTenure' | 'return pairs.map(ClaimTenure::new);' || true
        'the slot re-decorating' | 'RemoteOutageSignalingBaseRefGit' |
                'git.withBaseRefs(new RemoteOutageSignalingBaseRefGit(git.baseRefs(), gate));' || true
        'the owner accessor' | 'ClaimTenure' | 'var tenure = heartbeat.tenure();' || false
        'a longer type name' | 'SlotWiring' | 'var parts = new SlotWiringParts(a, b);' || false
        'a javadoc mention' | 'SlotWiring' | ' * Never {@code new SlotWiring(...)} in a constructor.' || false
        'a trailing comment' | 'ClaimTenure' | 'var t = heartbeat.tenure(); // not new ClaimTenure(' || false
    }

    /** Whether this source spells a construction of {@code type}, outside comments. */
    private static boolean constructs(String type, String source) {
        source.readLines().any { line ->
            def code = RepoSourceTree.codeOnly(line)
            code.contains("new ${type}(") || code.contains("${type}::new")
        }
    }
}
