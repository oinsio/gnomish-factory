package com.github.oinsio.gnomish.distribution

import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The weekly base rebuild's selection, {@code scripts/latest-final-release.sh} (FR8 of
 * add-sandbox-base-image; design D6, D15), driven over the committed {@code gh} presets of the
 * stand-in library ({@code tables/gh.params}), which answer the raw JSON of {@code gh release list}
 * from the library's {@code data/stdout-gh}. On GitHub the selection meets the real release list
 * once a week, so a regression — the latest-created release taken for the highest, a pre-release
 * rebuilt as a final one, a failure read as "no release" — would stay unseen until it moved
 * {@code X.Y} to the wrong source.
 *
 * <p>The stand-in reaches the script as {@code gh} on the {@code PATH}, through a per-run link that
 * records each invocation, so every feature also reads what gh was asked; {@code jq} is the
 * machine's own, as on the runner.
 */
class LatestFinalReleaseScriptSpec extends Specification {

    private static final Path SCRIPT = RepoSourceTree.repoRoot().resolve('scripts/latest-final-release.sh')

    /** The listing the script asks for: drafts and pre-releases excluded, raw JSON, no {@code --jq}. */
    private static final String LISTING = 'release list --exclude-drafts --exclude-pre-releases --limit 1000 ' +
    '--json tagName,isDraft,isPrerelease'

    @TempDir
    Path tmp

    Path gh

    def setup() {
        assert System.getenv('PATH').split(File.pathSeparator).any {
            Files.isExecutable(Path.of(it, 'jq'))
        }: 'no jq on the PATH: scripts/latest-final-release.sh selects with it, as on the runner'
    }

    // Scenario: sandbox/sandbox-base-image / The weekly rebuild takes the highest final release
    def "FR8: among finals, a draft, a pre-release and a later patch of an older line, v0.2.0 is rebuilt"() {
        when:
        def run = select('gh-releases-mixed')

        then: 'the highest final version, not the latest created, with the tag and the version output'
        run.exit == 0
        run.output == 'tag=v0.2.0\nversion=0.2.0\n'
        run.outputs == 'tag=v0.2.0\nversion=0.2.0\n'
        asked() == [LISTING]
    }

    // FR8 (design D15): versions are compared as numbers; tags not of the form vX.Y.Z are ignored
    def "FR8: from the #listing listing the script selects #tag"() {
        when:
        def run = select(preset)

        then:
        run.exit == 0
        run.output == "tag=${tag}\nversion=${tag - 'v'}\n"
        asked() == [LISTING]

        where:
        listing | preset || tag
        'numeric' | 'gh-releases-numeric' || 'v0.10.0'
        'malformed tag names' | 'gh-releases-malformed' || 'v0.2.0'
    }

    // FR8 (design D15): before the first final release the schedule stays green and builds nothing
    def "FR8: with #listing the script notes it, outputs an empty tag and exits 0"() {
        when:
        def run = select(preset)

        then:
        run.exit == 0
        run.output == '::notice title=Latest final release::no final release exists yet, so the weekly rebuild has ' +
                'nothing to build\ntag=\nversion=\n'
        run.outputs == 'tag=\nversion=\n'

        where:
        listing | preset
        'no release at all' | 'gh-releases-none'
        'only a draft and a pre-release' | 'gh-releases-only-unfinal'
    }

    // FR8 (design D15): a failure is never read as "no release"
    def "FR8: gh failing exits 1 with its error and an annotation naming the re-run, and outputs nothing"() {
        when:
        def run = select('gh-releases-unauthorized')

        then:
        run.exit == 1
        run.output == StandIn.data('stderr-gh#unauthorized') +
                '::error title=Latest final release::gh release list failed (its error is printed above), so the release ' +
                'to rebuild is unknown and nothing was built; re-run once the GitHub API answers\n'
        run.outputs == ''
    }

    // FR8 (design D15): a listing jq cannot read is a failure too, never an empty selection
    def "FR8: a listing that is not JSON exits 1 naming jq and the re-run, and outputs nothing"() {
        when:
        def run = select('gh-releases-not-json')

        then:
        run.exit == 1
        run.output.endsWith('::error title=Latest final release::jq could not read the listing gh release list printed ' +
                '(its error is printed above), so the release to rebuild is unknown and nothing was built; re-run once the ' +
                'GitHub API answers\n')
        run.outputs == ''
    }

    // FR8: the workflow passes no argument; one is a usage error that asks gh nothing
    def "FR8: an argument fails with exit 2 and asks gh nothing"() {
        given:
        gh = StandIn.link(tmp.resolve('gh'), 'gh-releases-mixed')

        when:
        def run = script(['v0.2.0'])

        then:
        run.exit == 2
        run.output.contains("usage: ${SCRIPT}")
        !Files.exists(StandIn.log(gh))
    }

    private Map select(String preset) {
        gh = StandIn.link(tmp.resolve('gh'), preset)
        script([])
    }

    /** Each recorded invocation's arguments, in order. */
    private List<String> asked() {
        StandIn.log(gh).toFile().readLines().findAll {
            it.startsWith('argv=')
        }.collect {
            it - 'argv='
        }
    }

    private Map script(List<String> args) {
        Path outputs = tmp.resolve('github-output')
        Files.writeString(outputs, '')
        def builder = new ProcessBuilder(['sh', SCRIPT.toString()] + args)
        .redirectErrorStream(true)
        .redirectInput(ProcessBuilder.Redirect.from(new File('/dev/null')))
        def env = TestChildEnvironment.cleared(builder)
        env.PATH = "${tmp}${File.pathSeparator}${env.PATH}".toString()
        env.GITHUB_OUTPUT = outputs.toString()
        def process = builder.start()
        def output = process.inputStream.text
        [exit: process.waitFor(), output: output, outputs: Files.readString(outputs)]
    }
}
