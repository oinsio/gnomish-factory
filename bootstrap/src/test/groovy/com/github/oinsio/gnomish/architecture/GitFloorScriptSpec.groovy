package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.gittransfer.GitVersion
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Matcher
import spock.lang.Specification

/**
 * The one git floor, held at its shell ends (single-owner row 3 of add-sandbox-base-image, design
 * D8, D9): {@link GitVersion#FLOOR} is the floor the factory judges both its own git and a box's
 * by, and {@code scripts/install-git.sh} builds the git the factory's CI runs on and the sandbox
 * base image carries. The script's pinned {@code VERSION} is that floor, so a floor raise is one
 * edit of the constant and one of the script, and this spec fails until both are made.
 *
 * <p>The script also refuses its own result below the floor, with the same {@code awk} test the CI
 * workflows apply to the runner's git; those tests spell the floor's minor number by hand, so they
 * are held to the constant here too.
 *
 * <p>Implements FR11 of add-sandbox-base-image.
 */
class GitFloorScriptSpec extends Specification {

    /** The floor test's spelling: {@code v[1] == <major> && v[2] >= <minor>}. */
    private static final String FLOOR_TEST = /v\[1\] == (\d+) && v\[2\] >= (\d+)/

    /** The files that run the floor test, each relative to the repository root. */
    private static final List<String> FLOOR_TEST_FILES = [
        'scripts/install-git.sh',
        '.github/workflows/ci.yml',
        '.github/workflows/pitest-nightly.yml',
    ]

    // FR11 (design, single-owner row 3): the version the script builds is the factory's floor
    def "FR11: scripts/install-git.sh builds exactly GitVersion.FLOOR"() {
        given:
        def script = file('scripts/install-git.sh')

        when:
        Matcher pinned = script =~ /(?m)^VERSION=(\S+)$/

        then: 'one pinned version, and it is the floor'
        pinned.size() == 1
        pinned[0][1] == GitVersion.FLOOR.toString()

        and: 'the script\'s own refusal names the same floor'
        script.contains("below the factory's floor (${GitVersion.FLOOR.major()}.${GitVersion.FLOOR.minor()})")
    }

    // FR11: every hand-spelled floor test agrees with the constant
    def "FR11: the floor test in #path judges by GitVersion.FLOOR"() {
        when:
        Matcher tests = file(path) =~ FLOOR_TEST

        then: 'the file runs the test, and every spelling of it names the floor'
        tests.size() >= 1
        tests.collect { [it[1] as int, it[2] as int] }.every {
            it == [
                GitVersion.FLOOR.major(),
                GitVersion.FLOOR.minor()
            ]
        }

        where:
        path << FLOOR_TEST_FILES
    }

    private static String file(String relative) {
        Path path = RepoSourceTree.repoRoot().resolve(relative)
        assert Files.isRegularFile(path): "${relative} is missing under the repository root"
        Files.readString(path)
    }
}
