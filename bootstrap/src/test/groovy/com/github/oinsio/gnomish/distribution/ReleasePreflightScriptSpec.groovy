package com.github.oinsio.gnomish.distribution

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The release workflow's gate, {@code scripts/release-preflight.sh} (FR1, FR2, NFR-O1 of
 * add-release-pipeline; design D6, steps 1-3), driven through every red path. On GitHub it only
 * ever meets a well-shaped tag on a green commit, so a regression in it would stay unseen until a
 * real release went out red, or a red commit went out as a release.
 *
 * <p>The repository is real: a local one whose {@code origin/main} is set by hand. {@code gh} is a
 * stub on the {@code PATH} that holds a list of runs, newest first, and applies the filters it is
 * given the way {@code gh run list} does — so a query that drops the branch or event filter sees
 * the runs that filter would have hidden. Every call is logged.
 */
class ReleasePreflightScriptSpec extends Specification {

    private static final Path SCRIPT = RepoSourceTree.repoRoot().resolve('scripts/release-preflight.sh')

    private static final String GH_STUB = ReleasePreflightScriptSpec.getResource('/release-preflight/gh-stub.sh').text

    @TempDir
    Path tmp

    Path repo
    String onMain
    String offMain
    List<Map> runs = []

    def setup() {
        // The gh stub filters its runs with jq; without it every call exits 127 and the script
        // reports a gh failure, so a missing tool would read as a broken script.
        assert System.getenv('PATH').split(File.pathSeparator).any {
            Files.isExecutable(Path.of(it, 'jq'))
        }: 'no jq on the PATH: the gh stub (release-preflight/gh-stub.sh) needs it'
        repo = Files.createDirectories(tmp.resolve('repo'))
        git('init', '--quiet', '--initial-branch=main')
        onMain = commit('on main')
        git('update-ref', 'refs/remotes/origin/main', onMain)
        git('checkout', '--quiet', '-b', 'feature')
        offMain = commit('only on a branch')
        def bin = Files.createDirectories(tmp.resolve('bin'))
        Files.writeString(bin.resolve('gh'), GH_STUB).toFile().setExecutable(true)
    }

    // FR1: the "Malformed tag stops early" scenario — before reachability, before any API call
    def "FR1: tag '#tag' fails the shape check naming the expected shape, before any other check"() {
        when:
        def run = preflight(tag, onMain)

        then:
        run.exit == 1
        run.output.contains("::error title=Tag shape::tag '${tag}' is not vMAJOR.MINOR.PATCH")
        run.output.contains("git push --delete origin ${tag}")
        !Files.exists(tmp.resolve('gh.log'))

        where:
        tag << [
            'v0.2',
            '0.2.0',
            'v0.2.0.1',
            'v01.2.0',
            'v0.2.0-',
            'v0.2.0+build',
            'release-0.2.0'
        ]
    }

    // FR1, FR3: a well-shaped tag yields the product version, and a suffix marks a pre-release
    def "FR1: tag #tag passes and yields version #version, prerelease #prerelease"() {
        given:
        runs << run(onMain, 'main', 'push', 'completed', 'success')
        def outputs = tmp.resolve('github-output')

        when:
        def result = preflight(tag, onMain, [GITHUB_OUTPUT: outputs.toString()])

        then:
        result.exit == 0
        Files.readString(outputs) == "version=${version}\nprerelease=${prerelease}\n"

        where:
        tag | version | prerelease
        'v0.1.0' | '0.1.0' | false
        'v10.20.30' | '10.20.30' | false
        'v0.2.0-rc.1' | '0.2.0-rc.1' | true
    }

    // FR2: a commit that never reached main is refused before the CI query
    def "FR2: a commit not reachable from origin/main fails naming the fix"() {
        when:
        def run = preflight('v0.1.0', offMain)

        then:
        run.exit == 1
        run.output.contains("::error title=Commit on main::commit ${offMain} is not reachable from main")
        !Files.exists(tmp.resolve('gh.log'))
    }

    // FR2, NFR-O1: the "Red commit is not released" scenario and its siblings
    def "FR2: the preflight fails when the main push run is #state"() {
        given:
        runs << run(onMain, 'main', 'push', status, conclusion)

        when:
        def run = preflight('v0.1.0', onMain)

        then:
        run.exit == 1
        run.output.contains("::error title=CI status::the ci.yml run for ${onMain} ${verdict} (https://example.test/runs/${onMain})")

        where:
        state | status | conclusion | verdict
        'failed' | 'completed' | 'failure' | "concluded 'failure'"
        'cancelled' | 'completed' | 'cancelled' | "concluded 'cancelled'"
        'in progress' | 'in_progress' | '' | 'is still in_progress'
    }

    def "FR2: the preflight fails when the commit has no CI run on a push to main"() {
        given: 'only runs the branch and event filters must hide'
        runs << run(onMain, 'feature', 'push', 'completed', 'success')
        runs << run(onMain, 'main', 'pull_request', 'completed', 'success')

        when:
        def run = preflight('v0.1.0', onMain)

        then:
        run.exit == 1
        run.output.contains("::error title=CI status::no ci.yml run on a push to main exists for ${onMain}")
    }

    // FR2: design D6 — a run on another ref is never the one judged; red without the filters
    def "FR2: an in-progress run of the same commit on another ref does not shadow the green main run"() {
        given: 'the newest run is a tag-ref run still in progress'
        runs << run(onMain, 'v0.1.0', 'push', 'in_progress', '')
        runs << run(onMain, 'main', 'push', 'completed', 'success')

        when:
        def run = preflight('v0.1.0', onMain)

        then:
        run.exit == 0
        Files.readString(tmp.resolve('gh.log')).contains("--commit ${onMain} --branch main --event push")
    }

    // NFR-O1: an unreachable API is a named failure, not a pass on an empty listing
    def "NFR-O1: a failing gh call fails the CI check naming the re-run"() {
        when:
        def run = preflight('v0.1.0', onMain, [GH_STUB_FAIL: '1'])

        then:
        run.exit == 1
        run.output.contains("::error title=CI status::could not list the ci.yml runs for ${onMain}")
    }

    private Map run(String sha, String branch, String event, String status, String conclusion) {
        [workflow: 'ci.yml', headSha: sha, headBranch: branch, event: event, status: status,
            conclusion: conclusion, url: "https://example.test/runs/${sha}".toString()]
    }

    private Map preflight(String tag, String sha, Map<String, String> extraEnv = [:]) {
        def runsFile = tmp.resolve('runs.json')
        new ObjectMapper().writeValue(runsFile.toFile(), runs)
        def process = new ProcessBuilder([
            'bash',
            SCRIPT.toString(),
            tag,
            sha
        ]).directory(repo.toFile()).redirectErrorStream(true)
        def env = process.environment()
        env.remove('GITHUB_OUTPUT')
        env.PATH = "${tmp.resolve('bin')}:${env.PATH}".toString()
        env.GH_STUB_LOG = tmp.resolve('gh.log').toString()
        env.GH_STUB_RUNS = runsFile.toString()
        env.putAll(extraEnv)
        def started = process.start()
        def output = started.inputStream.text
        [exit: started.waitFor(), output: output]
    }

    private String commit(String message) {
        git('commit', '--quiet', '--allow-empty', '-m', message)
        git('rev-parse', 'HEAD').trim()
    }

    private String git(String... args) {
        def process = new ProcessBuilder(['git'] + args.toList()).directory(repo.toFile()).redirectErrorStream(true).start()
        def output = process.inputStream.text
        assert process.waitFor() == 0: "git ${args.join(' ')} failed: ${output}"
        output
    }
}
