package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.ModuleBuildFile
import spock.lang.Specification

/**
 * FR2, FR3 of migrate-verify-to-executor-contract (design D20): the pending key is one type on both
 * sides of the engine port, because both modules that carry it declare the one JDK-only leaf that
 * owns it, and the executor contract reaches nothing internal beyond its two JDK-only leafs. The
 * emptiness of the leaf itself is the leaf table of {@link ModuleBuildFileSpec}; this is the other
 * half — the two edges into it — read from the build files as data, as that spec reads them.
 */
class PendingKeyLeafEdgeSpec extends Specification {

    // Scenario: build-system/module-layering / The pendingkey leaf stays empty of dependencies
    // Scenario: build-system/module-layering / The executor contract depends only on two JDK-only leafs
    def "the domain and the executor contract both declare the pendingkey leaf, and the contract reaches only its two leafs"() {
        given: 'the two build files, read as data'
        def domain = ModuleBuildFile.textOf(':domain')
        def contract = ModuleBuildFile.textOf(':executor-contract')

        expect: 'both really exist and were parsed, so the scan is not vacuous'
        !domain.isEmpty()
        !contract.isEmpty()

        and: 'each declares the leaf on its api, so the key is one type on both sides of the engine port'
        "api project(':pendingkey')" in ModuleBuildFile.productionDependencies(domain)
        "api project(':pendingkey')" in ModuleBuildFile.productionDependencies(contract)
        ':pendingkey' in ModuleBuildFile.allowedProjects(domain)

        and: 'the executor contract reaches exactly the two JDK-only leafs, nothing else internal'
        ModuleBuildFile.allowedProjects(contract) == [
            ':untrustedtext',
            ':pendingkey'
        ] as Set
        ModuleBuildFile.productionDependencies(contract).findAll {
            it.contains('project(')
        } ==
        [
            "api project(':untrustedtext')",
            "api project(':pendingkey')"
        ] as Set
    }
}
