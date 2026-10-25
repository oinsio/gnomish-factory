package com.github.oinsio.gnomish.distribution

import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The release workflow's check of the stamped recipe, {@code scripts/check-recipe-stamp.sh} (FR9 of
 * add-sandbox-base-image; design D11, D15; single-owner row 4, the release branch of the stamp,
 * which no CI test build reaches), driven over unpacked archive trees. The trees hold the
 * repository's own recipe files with the {@code ARG GNOMISH_BASE_TAG=} line set as each feature
 * needs, so the check judges the files a release ships, not a toy.
 */
class RecipeStampArchiveScriptSpec extends Specification {

    private static final Path SCRIPT = RepoSourceTree.repoRoot().resolve('scripts/check-recipe-stamp.sh')

    private static final String ARG = 'ARG GNOMISH_BASE_TAG='

    @TempDir
    Path tmp

    // FR9: both shipped files carry the handed base tag, a final line or a whole pre-release
    def "FR9: an archive whose Dockerfile and README carry base tag #baseTag passes"() {
        given:
        def archive = unpacked([Dockerfile: baseTag, 'README.md': baseTag])

        when:
        def run = check(archive, baseTag)

        then:
        run.exit == 0
        run.output == ''

        where:
        baseTag << ['0.2', '0.3.0-rc.1']
    }

    // FR9: a file the stamp did not reach fails naming that file, and only that one
    def "FR9: a #file still carrying the repository default fails naming the file and what it carries"() {
        given:
        def stamps = [Dockerfile: '0.2', 'README.md': '0.2']
        stamps[file] = '0.1'
        def archive = unpacked(stamps)

        when:
        def run = check(archive, '0.2')

        then:
        run.exit == 1
        run.output == "::error title=Recipe stamp::${archive}/share/sandbox-image/${file} carries '${ARG}0.1', " +
                "expected '${ARG}0.2'; the release's base tag did not reach the shipped recipe\n"

        where:
        file << ['Dockerfile', 'README.md']
    }

    // FR9: the X.Y of a pre-release is not its base tag (design D11)
    def "FR9: an archive stamped with the line of a pre-release fails against its whole version"() {
        given:
        def archive = unpacked([Dockerfile: '0.3', 'README.md': '0.3'])

        when:
        def run = check(archive, '0.3.0-rc.1')

        then: 'both files are reported before the exit'
        run.exit == 1
        run.output.readLines().size() == 2
        run.output.readLines().every {
            it.contains("carries '${ARG}0.3', expected '${ARG}0.3.0-rc.1'")
        }
    }

    // FR9: a missing file and a file without the line are failures, never a pass
    def "FR9: a missing README and a Dockerfile without the line both fail, each named"() {
        given:
        def archive = unpacked([Dockerfile: '0.2'])
        def dockerfile = archive.resolve('share/sandbox-image/Dockerfile')
        Files.write(dockerfile, Files.readAllLines(dockerfile).findAll {
            !it.startsWith(ARG)
        })

        when:
        def run = check(archive, '0.2')

        then:
        run.exit == 1
        run.output == "::error title=Recipe stamp::${dockerfile} has no '${ARG}' line; expected '${ARG}0.2'\n" +
                "::error title=Recipe stamp::${archive}/share/sandbox-image/README.md is missing from the archive\n"
    }

    def "FR9: a blank base tag is refused as a usage error"() {
        when:
        def run = check(unpacked([Dockerfile: '0.2', 'README.md': '0.2']), '')

        then:
        run.exit == 2
        run.output.contains('<unpacked archive> <base tag>')
    }

    /** An unpacked archive whose recipe files are the repository's, each stamped with its tag. */
    private Path unpacked(Map<String, String> stamps) {
        def archive = tmp.resolve('gnomish-0.2.0')
        def recipe = Files.createDirectories(archive.resolve('share/sandbox-image'))
        stamps.each { name, tag ->
            def source = RepoSourceTree.repoRoot().resolve("docs/examples/sandbox-image/${name}")
            Files.write(recipe.resolve(name), Files.readAllLines(source).collect {
                it.startsWith(ARG) ? ARG + tag : it
            })
        }
        archive
    }

    private Map check(Path archive, String baseTag) {
        def builder = new ProcessBuilder([
            'sh',
            SCRIPT.toString(),
            archive.toString(),
            baseTag
        ]).redirectErrorStream(true)
        TestChildEnvironment.cleared(builder)
        def process = builder.start()
        def output = process.inputStream.text
        [exit: process.waitFor(), output: output]
    }
}
