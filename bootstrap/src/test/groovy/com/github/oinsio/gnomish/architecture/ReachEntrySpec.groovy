package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.ModuleBuildFile
import spock.lang.Specification

/**
 * A layering allowlist entry for a leaf the module does not declare says how the leaf arrives
 * (FR11 of split-logtext-leaves; FR2 of own-git-transfer-argv): {@code verifyModuleLayering} walks
 * the transitive graph, so it demands the entry, and the comment above it is the only record of
 * which declared edge carries the leaf. The composition root is exempt: it reaches everything.
 */
class ReachEntrySpec extends Specification {

    /** The leafs that arrive transitively in most modules. */
    private static final List<String> TRANSITIVE_LEAFS = [
        ':untrustedtext',
        ':operatorevent',
        ':operatorconfig',
        ':gittransfer'
    ]

    /**
     * The declared edges a transitive leaf may arrive through; {@code :gitobjects} carries
     * {@code :gittransfer} to {@code :application} (FR1 of own-git-invocation-policy).
     */
    private static final List<String> CARRIERS = [
        ':domain',
        ':gnomish-plugin-api',
        ':application',
        ':sandbox:core',
        ':logtext',
        ':gitobjects',
        ':sandbox:docker',
        ':adapters:git'
    ]

    // Scenario: build-system/module-layering / A reach entry names the edge it arrives through
    def "every undeclared transitive-leaf entry outside :bootstrap carries a comment naming its carrier"() {
        given:
        def modules = ModuleBuildFile.includedProjects() - [':bootstrap']

        when:
        def entries = modules.collectMany { module ->
            undeclaredEntries(module)
        }

        then: 'the scan reached transitive entries'
        entries.size() >= KNOWN_TRANSITIVE_ENTRIES

        and: 'each names the edge it arrives through'
        entries.findAll { entry ->
            !CARRIERS.any {
                entry.comment.contains("`${it}`")
            }
        }
        .collect { "${it.module} -> ${it.leaf}".toString() } == []
    }

    /** A mis-parsed allowlist would pass vacuously. */
    private static final int KNOWN_TRANSITIVE_ENTRIES = 10

    /** The allowlist entries for a transitive leaf the module does not itself declare. */
    private static List<Map<String, String>> undeclaredEntries(String module) {
        def declared = ModuleBuildFile.productionDependencies(ModuleBuildFile.textOf(module))
        def lines = layeringLines(ModuleBuildFile.fileOf(module).text)
        def found = []
        lines.eachWithIndex { line, index ->
            def leaf = TRANSITIVE_LEAFS.find {
                line.trim() == "'${it}',".toString()
            }
            if (leaf != null && !declared.any {
                        it.contains("project('${leaf}')")
                    }) {
                found << [module: module, leaf: leaf, comment: commentAbove(lines, index)]
            }
        }
        found
    }

    /** The lines of a build file's {@code layering} block, comments kept. */
    private static List<String> layeringLines(String text) {
        def start = text.indexOf('\nlayering {')
        start < 0 ? [] : text.substring(start, text.indexOf('\n}', start)).readLines()
    }

    /** The contiguous comment lines directly above line {@code index}, joined. */
    private static String commentAbove(List<String> lines, int index) {
        def comment = []
        for (int i = index - 1; i >= 0 && lines[i].trim().startsWith('//'); i--) {
            comment.add(0, lines[i].trim())
        }
        comment.join(' ')
    }
}
