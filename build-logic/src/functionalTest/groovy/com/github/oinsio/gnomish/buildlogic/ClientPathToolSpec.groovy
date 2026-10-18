package com.github.oinsio.gnomish.buildlogic

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link ClientPathTool}, the one lookup of a build-side tool on the client's {@code PATH} (design
 * D14 of fix-terminal-receipt-convergence as amended 2026-10-18, task 7.5), specified directly. The
 * executable a {@code PATH} entry holds is a link onto the committed stand-in script — the lookup
 * runs nothing, so any executable regular file serves.
 */
class ClientPathToolSpec extends Specification {

    @TempDir
    Path home

    def "FR14: a bare name resolves to its absolute path in the first PATH entry that holds it as an executable"() {
        given:
        Path plain = dir('plain')
        plain.resolve('docker').text = 'not executable\n'
        Files.createDirectories(dir('directory').resolve('docker'))
        Path first = tool('first')
        tool('second')

        expect:
        ClientPathTool.resolve('docker', [plain, home.resolve('directory'), first, home.resolve('second')].join(':')) ==
                first.resolve('docker').toString()
    }

    def "FR14: an entry with a trailing slash yields a clean path"() {
        expect:
        ClientPathTool.resolve('docker', "${tool('slash')}/") == home.resolve('slash/docker').toString()
    }

    def "FR14: an empty or relative entry is skipped — it would resolve in the daemon's working directory (#path)"() {
        expect:
        ClientPathTool.resolve('docker', path) == null

        where:
        path << ['', ':', '.', 'bin', null]
    }

    def "FR14: a name holding a slash is the caller's path, taken as it is when it is executable"() {
        given:
        String chosen = tool('chosen').resolve('docker').toString()

        expect:
        ClientPathTool.resolve(chosen, null) == chosen
        ClientPathTool.resolve(home.resolve('missing/docker').toString(), "${home.resolve('chosen')}") == null
    }

    // The warning's text is pinned where a build shows it: TestDockerEnvironmentFunctionalSpec and
    // MutationScopeGitPathFunctionalSpec.
    def "FR14: locate answers like resolve, and a tool it cannot find is null"() {
        given:
        String path = tool('found').toString()

        expect:
        ClientPathTool.locate('docker', path, 'unused') == home.resolve('found/docker').toString()
        ClientPathTool.locate('git', path, 'unused') == null
    }

    /** A directory under the temporary home holding {@code docker}, a link onto the committed script. */
    private Path tool(String name) {
        Path parent = dir(name)
        Files.createSymbolicLink(parent.resolve('docker'),
                Path.of(GradleRunnerSupport.requiredProperty('gnomish.standInLibrary')).resolve('stand-in.sh'))
        parent
    }

    private Path dir(String name) {
        Files.createDirectories(home.resolve(name))
    }
}
