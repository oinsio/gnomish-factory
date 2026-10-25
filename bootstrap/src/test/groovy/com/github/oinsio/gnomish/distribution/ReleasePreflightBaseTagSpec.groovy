package com.github.oinsio.gnomish.distribution

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The pure mode of {@code scripts/release-preflight.sh} (FR8, FR9 of add-sandbox-base-image;
 * design D11): {@code --base-tag <tag>} runs the tag's shape check only and prints the release's
 * base tag — {@code X.Y} of a final release, the whole version of a pre-release — for the weekly
 * base rebuild, which must not depend on the reachability or CI checks of a months-old tag.
 *
 * <p>It runs in a directory that is no repository with a {@code PATH} naming one empty directory, so
 * a {@code git} or {@code gh} call — any command that is not a shell builtin — fails the run
 * ({@code set -e}). The full run's {@code base_tag} output is pinned in
 * {@link ReleasePreflightScriptSpec}; the equality with the build's base tag in
 * {@code BaseTagIdentityFunctionalSpec}.
 */
class ReleasePreflightBaseTagSpec extends Specification {

    private static final Path SCRIPT = RepoSourceTree.repoRoot().resolve('scripts/release-preflight.sh')

    @TempDir
    Path tmp

    def "FR9: --base-tag #tag prints base tag #baseTag with no git and no gh call"() {
        given:
        def outputs = tmp.resolve('github-output')

        when:
        def result = runBaseTag(tag, [GITHUB_OUTPUT: outputs.toString()])

        then:
        result.exit == 0
        result.output == "base_tag=${baseTag}\n"
        Files.readString(outputs) == "base_tag=${baseTag}\n"

        where:
        tag | baseTag
        'v0.4.1' | '0.4'
        'v10.20.30' | '10.20'
        'v0.4.1-rc.1' | '0.4.1-rc.1'
        'v1.0.0-beta.2' | '1.0.0-beta.2'
    }

    def "FR9: --base-tag refuses a malformed tag with the shape check's error: #tag"() {
        when:
        def result = runBaseTag(tag, [:])

        then:
        result.exit == 1
        result.output.contains("::error title=Tag shape::tag '${tag}' is not vMAJOR.MINOR.PATCH")
        !result.output.contains('base_tag=')

        where:
        tag << [
            'v0.2',
            '0.2.0',
            'v0.2.0-',
            'v01.2.0'
        ]
    }

    private Map runBaseTag(String tag, Map<String, String> extraEnv) {
        def process = new ProcessBuilder([
            'bash',
            SCRIPT.toString(),
            '--base-tag',
            tag
        ])
        .directory(tmp.toFile()).redirectErrorStream(true)
        // the pure mode needs nothing from the parent: a PATH naming an empty directory, and a
        // GITHUB_OUTPUT only where a feature sets one
        def env = process.environment()
        env.clear()
        env.PATH = Files.createDirectories(tmp.resolve('empty-path')).toString()
        env.putAll(extraEnv)
        def started = process.start()
        def output = started.inputStream.text
        [exit: started.waitFor(), output: output]
    }
}
