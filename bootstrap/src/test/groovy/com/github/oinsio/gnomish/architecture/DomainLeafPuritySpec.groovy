package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.ModuleBuildFile
import spock.lang.Specification

/**
 * FR6, design D5 of split-logtext-leaves: {@code :domain} may depend on a JDK-only leaf, and on
 * nothing else internal. This gate holds the build to the <em>definition</em> rather than to a list
 * of blessed module names — "declares no internal module and no external library" — so the next
 * leaf the domain needs is admitted by adding the edge that uses it, and a leaf that quietly
 * acquires a dependency of its own fails here instead of pushing a logging API, a framework or a
 * serialization library into the domain behind the layering gate's back.
 *
 * <p>Why not inside {@link DomainPuritySpec}: that one is an ArchUnit rule over compiled production
 * bytecode, and forbidden <em>packages</em> are all it can see. Whether a project declares an
 * external artifact is a question about build files, which this reads through {@code repoRoot} —
 * the same medium and the same module as {@link ModuleBuildFileSpec}.
 *
 * <p>Lives in {@code :bootstrap} because that is the module whose {@code test} task wires
 * {@code repoRoot} and declares the build metadata as an input, so editing a leaf's build script
 * really does re-run this gate.
 */
class DomainLeafPuritySpec extends Specification {

    /** The leafs `:domain` reaches today; a mis-parsed build file would make every case vacuous. */
    private static final int KNOWN_DOMAIN_LEAFS = 1

    // FR6: the domain's reach is stated as data, and this gate reads that data rather than a copy
    def "the domain's allowlist is read, non-empty, and internal to the build"() {
        given:
        def allowed = ModuleBuildFile.allowedProjects(ModuleBuildFile.textOf('domain'))

        expect: 'the scan really reached the allowlist rather than an unparsed block'
        allowed.size() >= KNOWN_DOMAIN_LEAFS

        and: 'and every entry is a project path this build defines'
        allowed.every {
            it.startsWith(':') && !ModuleBuildFile.textOf(it).isEmpty()
        }
    }

    // D5: every project the domain reaches satisfies the JDK-only-leaf definition, both halves
    def "#leaf, named by the domain's allowlist, is a JDK-only leaf"() {
        given:
        def code = ModuleBuildFile.textOf(leaf)

        expect: 'it reaches no internal project of its own — the recursion stops at the leaf'
        ModuleBuildFile.allowedProjects(code) == [] as Set

        and: 'and it declares no production-scope artifact — no logging API, framework or format'
        ModuleBuildFile.productionDependencies(code) == [] as Set

        where:
        leaf << ModuleBuildFile.allowedProjects(ModuleBuildFile.textOf('domain'))
    }

    // D5: the detector is the gate — a seeded leaf must fail it, or a green run means nothing
    def "a seeded leaf that acquired an internal edge is detected"() {
        given: 'a leaf build script that reaches a project of its own'
        def seeded = '''
            dependencies {
            }
            layering {
                allowedProjects = [':logtext']
            }
        '''.stripIndent()

        expect: 'the internal half of the definition reports it'
        ModuleBuildFile.allowedProjects(seeded) == [':logtext'] as Set
    }

    // D5: the other half — an external artifact is what would ride into the domain
    def "a seeded leaf that acquired a production-scope artifact is detected: #declaration"() {
        given: 'a leaf build script declaring an artifact beside a test-scope one, which is allowed'
        def seeded = """
            dependencies {
                ${declaration}
                testImplementation project(':test-fixtures')
            }
            layering {
                allowedProjects = []
            }
        """.stripIndent()

        expect: 'the external half of the definition reports exactly the production declaration'
        ModuleBuildFile.productionDependencies(seeded) == [declaration] as Set

        where:
        declaration << [
            'api libs.slf4j.api',
            'implementation platform(libs.spring.boot.dependencies)',
            "implementation project(':untrustedtext')"
        ]
    }
}
