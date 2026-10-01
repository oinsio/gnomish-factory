package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * FR10 of add-project-registry (serve-observability, "begin with the project name"): a factory
 * process's instance id is {@code <project>-<instance>-<suffix>}, and the two names are joined in
 * one place, {@code ProjectScope.mintInstanceId}. {@code InstanceId.generate} is therefore called
 * by that owner and by nothing else in production: a command minting its own id from the instance
 * name alone would claim under an id that does not name the project.
 *
 * <p>The scan asserts it reached the whole production tree and the owner, the shape {@link
 * ProjectDirResolutionBoundarySpec} uses.
 */
class InstanceIdMintBoundarySpec extends Specification {

    private static final String OWNER = 'application/src/main/java/com/github/oinsio/gnomish/app/ProjectScope.java'

    // FR10: no production source outside the owner mints an instance id
    def "FR10: InstanceId.generate is called only by ProjectScope"() {
        given: 'every production source, comments stripped'
        def sources = RepoSourceTree.productionSources()
        def minters = sources.findAll { mints(RepoSourceTree.code(it)) }
        .collect { RepoSourceTree.relative(it) }
        .sort()

        expect: 'the scan reached the whole production tree'
        sources.size() > RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        and: 'the owner is the one minter'
        minters == [OWNER]
    }

    // Over a clean tree the detector fires once; a seeded call must be found, a comment must not.
    def "the detector finds a seeded mint and leaves a commented one alone: #shape"() {
        expect:
        mints(RepoSourceTree.codeOnly(source)) == detected

        where:
        shape | source || detected
        'a direct mint' | 'InstanceId id = InstanceId.generate(properties.instanceName());' || true
        'a trailing comment' | 'InstanceId id = scope.mintInstanceId(); // was InstanceId.generate(name)' || false
        'the owner call' | 'InstanceId id = scope.mintInstanceId();' || false
    }

    private static boolean mints(String code) {
        code =~ /\bInstanceId\s*\.\s*generate\s*\(/
    }
}
