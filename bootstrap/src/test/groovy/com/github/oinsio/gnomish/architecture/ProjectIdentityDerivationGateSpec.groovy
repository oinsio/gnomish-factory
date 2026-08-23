package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * M3 of normalize-project-identity-url, as a gate rather than a one-off grep: the project identity
 * has exactly one derivation site and exactly one URL normalizer, and no call site digests a raw
 * {@code origin} URL.
 *
 * <p>Why a gate at all: the whole change is worth nothing if a second place ever hashes the remote
 * URL. A duplicate would silently re-introduce the orphaning this change exists to remove, and it
 * would do so invisibly — both digests are 12 hex characters, and only the objects stop matching.
 *
 * <p>Lives in {@code :bootstrap} for the same reason as {@link ModuleBuildFileSpec}: it is a
 * whole-tree source gate, and this is the module whose {@code test} task wires {@code repoRoot}.
 */
class ProjectIdentityDerivationGateSpec extends Specification {

    /** The single site allowed to turn a URL into an identity. */
    private static final String DERIVATION_SITE = 'ProjectIdentity.java'

    /** The single lexical normalizer. */
    private static final String NORMALIZER = 'OriginUrlNormalizer.java'

    /** Digesting anything at all: the construct that would fork a second identity derivation. */
    private static final Pattern DIGEST = Pattern.compile('MessageDigest|Hashing\\.|\\.hashCode\\(\\)\\s*\\+')

    // M3: exactly one production file computes a digest, and it is ProjectIdentity.
    def "exactly one production source derives an identity by digest"() {
        given:
        def sources = RepoSourceTree.productionSources()

        expect: 'the scan really reached the source tree'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        and:
        sources.findAll {
            DIGEST.matcher(RepoSourceTree.code(it)).find()
        }.collect {
            it.name
        } == [DERIVATION_SITE]
    }

    // M3: exactly one normalizer exists, and only the derivation site calls it — a second caller
    //     would be a second answer to "what is this project's identity".
    def "exactly one normalizer exists and only the derivation site calls it"() {
        given:
        def sources = RepoSourceTree.productionSources()

        expect:
        sources.count { it.name == NORMALIZER } == 1

        and:
        sources.findAll {
            RepoSourceTree.code(it).contains('OriginUrlNormalizer')
        }.collect {
            it.name
        }.toSorted() == [NORMALIZER, DERIVATION_SITE].toSorted()
    }

    // M3, FR1: no call site digests a raw URL — every consumer goes through ProjectIdentity, whose
    //     own normalization the specs in :application pin. Callers name resolve/resolveScope only.
    def "every consumer resolves through ProjectIdentity rather than hashing a URL itself"() {
        given:
        def callers = RepoSourceTree.productionSources().findAll {
            it.name != DERIVATION_SITE && RepoSourceTree.code(it).contains('ProjectIdentity')
        }

        expect: 'the two composition-root call sites named in the proposal, and no others'
        callers.collect { it.name }.toSorted() ==
        [
            'ContainerRunSupportFactory.java',
            'SandboxLifecyclePassFactory.java'
        ]

        and: 'each reaches the identity only through the resolver, never past it'
        callers.every {
            RepoSourceTree.code(it) =~ /ProjectIdentity\.resolve(Scope)?\(/
        }
    }
}
