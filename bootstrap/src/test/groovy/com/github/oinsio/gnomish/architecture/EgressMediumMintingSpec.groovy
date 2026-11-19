package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * FR15, FR17 of migrate-verify-to-executor-contract, design D16 as amended 2026-11-09 (row {@code
 * :httpegress} of the D9 single-owner table, "Enforced by"): the medium of a URL is minted where the
 * configuration is read and nowhere else, and an endpoint is admitted only by the guard.
 *
 * <ul>
 *   <li>{@code new ConnectionEndpoint(} appears in production code only in {@code ConnectionProfiles},
 *       the one reader that knows whether a value came from a profile or from the subsection;
 *   <li>the literal {@code EgressMedium.OPERATOR} appears only there and in the built-ins' one
 *       connection reader — a consumer that could spell it could declare its own URL granted;
 *   <li>{@code new AdmittedEndpoint(} appears only in {@code EgressAdmission}, so every {@code
 *       AdmittedEndpoint} a client holds was judged.
 * </ul>
 *
 * <p>The whole production tree is scanned, {@code :test-fixtures} included, comments stripped; every
 * allowlisted file is asserted reached and still spelling its shape, so a move cannot widen the gate.
 */
class EgressMediumMintingSpec extends Specification {

    private static final String PROFILES =
    'gnomish-plugin-api/src/main/java/com/github/oinsio/gnomish/app/ConnectionProfiles.java'

    private static final String ADMISSION =
    'httpegress/src/main/java/com/github/oinsio/gnomish/httpegress/EgressAdmission.java'

    /**
     * The one connection reader of the credentialed built-ins (task 3.3): it mints a {@code
     * factory.check.<name>} endpoint with the operator medium, for {@code github-actions} and {@code
     * scm-status} alike.
     */
    private static final String SCM_READER =
    'adapters/scm/src/main/java/com/github/oinsio/gnomish/adapter/scm/ScmConnectionReader.java'

    /** Each minting shape and the files allowed to spell it. */
    private static final Map<String, Set<String>> OWNERS = [
        (/\bnew\s+ConnectionEndpoint\s*\(/) : [PROFILES] as Set,
        (/\bEgressMedium\s*\.\s*OPERATOR\b/) : [PROFILES, SCM_READER] as Set,
        (/\bnew\s+AdmittedEndpoint\s*\(/) : [ADMISSION] as Set,
    ]

    // FR15, FR17, D16: the medium and the admission each have one minting site
    def "the shape #shape is spelled in production code only by its owners"() {
        given: 'every production source, comments stripped'
        def tree = productionCode()

        expect: 'the scan really reached the tree'
        tree.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        and: 'every owner is reached and still spells the shape'
        owners.findAll { !(tree[it] ?: '').find(shape) } == [] as Set

        and: 'no other file spells it'
        tree.findAll { path, code ->
            code.find(shape) && !owners.contains(path)
        }.keySet() == [] as Set

        where:
        shape << OWNERS.keySet()
        owners << OWNERS.values()
    }

    // The detector is the gate: a planted minting site is found, a mention is not
    def "the detector: #description"() {
        expect:
        (RepoSourceTree.stripComments(source).find(OWNERS.keySet()[index]) != null) == detected

        where:
        description | index | source || detected
        'a constructed connection endpoint' | 0 | 'var e = new ConnectionEndpoint(url, medium, List.of());' || true
        'a connection endpoint taken as a value' | 0 | 'void admit(ConnectionEndpoint endpoint) {}' || false
        'the operator medium spelled' | 1 | 'var m = EgressMedium.OPERATOR;' || true
        'the operator medium statically imported' | 1 | 'import static x.EgressMedium.OPERATOR;' || true
        'the law medium spelled' | 1 | 'var m = EgressMedium.LAW;' || false
        'an admitted endpoint constructed' | 2 | 'return new AdmittedEndpoint(url, key, admitted);' || true
        'a construction in a comment' | 2 | '// new AdmittedEndpoint(url, key, admitted)\nvar x = 1;' || false
    }

    /** Every production source by its relative path, comments stripped. */
    private static Map<String, String> productionCode() {
        RepoSourceTree.productionSources().collectEntries {
            [(RepoSourceTree.relative(it)): RepoSourceTree.code(it)]
        }
    }
}
