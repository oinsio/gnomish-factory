package com.github.oinsio.gnomish.adapter.git

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * The committed adversarial global git configuration every test runs under (design D11 of
 * own-git-transfer-argv; FR12, NFR-S2): {@code test-fixtures/src/main/resources/adversarial-gitconfig},
 * which {@code test-conventions} and {@code pitest-conventions} hand to git through
 * {@code GIT_CONFIG_GLOBAL} on every {@code Test} and {@code pitest} task. The JVM cannot set
 * its own environment, so the build does it; this class owns what the file means and offers
 * {@link #assertInEffect} so a spec proving isolation from an operator's configuration cannot
 * pass vacuously when run outside Gradle, where the file is not in force.
 *
 * <p>One line per key of the file — a key added there is documented here in the same change:
 *
 * <ul>
 *   <li>{@code alias.gnomish-probe} — the probe: prints {@link #PROBE_LINE}, and only this file
 *       defines it, so its answer is the proof that the file is the global configuration.
 *   <li>{@code submodule.recurse=true} — a fetch that honours it recurses into submodules and
 *       contacts their remotes; the owner's {@code --no-recurse-submodules} must defeat it.
 *   <li>{@code fetch.prune=true} — a fetch that honours it deletes every remote-tracking ref
 *       origin no longer has; a transfer must change exactly the ref it names.
 *   <li>{@code url."ext::touch .gnomish-ext-ran ".insteadOf=https://gnomish-rewrite.invalid/} —
 *       a rewrite of the sentinel host onto the {@code ext::} helper, whose command leaves
 *       {@link #EXT_RAN_MARKER} in git's working directory (the path after the sentinel lands as
 *       a second argument, so the marker's name survives). A transfer that lets the operator's
 *       rewrites apply, and allows {@code ext}, runs an operator-chosen command.
 *   <li>{@code credential.helper} — a shell function leaving {@link #CREDENTIAL_GET_MARKER} in
 *       git's working directory on the {@code get} action only: git also calls helpers with
 *       {@code store} after a successful URL-token authentication, which the Gitea lane performs,
 *       so a helper marking every action would mark that lane itself. A container or seed
 *       transfer that consults the operator's helpers leaks a credential path into the box.
 *   <li>{@code user.name}, {@code user.email} — not adversarial: the ambient identity a real
 *       deployment has and the production adapters commit with ({@link #IDENTITY_NAME},
 *       {@link #IDENTITY_EMAIL}). Without it every such commit fails with "Author identity
 *       unknown" wherever git cannot guess one from the account and host — a CI runner — while
 *       passing on a workstation that can, so the file supplies it on every machine alike.
 * </ul>
 *
 * <p>Every other key of an operator's real {@code ~/.gitconfig} — aliases, colours, includes —
 * is absent, and the developer's own identity is replaced by the one above.
 *
 * <p>Kept in sync with {@code com.github.oinsio.gnomish.build.AdversarialGitConfig} in
 * {@code build-logic}: both name the same variable ({@link #VARIABLE}) and the same committed
 * file ({@link #RELATIVE_PATH}). The build end sets them on every forked test JVM; this end
 * reads them back. No classpath joins the two, so {@code AdversarialGitConfigSpec} is what pins
 * the identity: the file git lists as global must equal the resource this class reads. Listed
 * in {@code .claude/rules/manual-sync-pairs.md} under "no shared classpath".
 */
final class AdversarialGitConfig {

    /** The variable git reads in place of {@code ~/.gitconfig}; set by the build, never by a spec. */
    static final String VARIABLE = 'GIT_CONFIG_GLOBAL'

    /** The resource name, and the file name the build's path must end with. */
    static final String FILE_NAME = 'adversarial-gitconfig'

    /** Where the file lives relative to the repository root — the path the build points at. */
    static final String RELATIVE_PATH = 'test-fixtures/src/main/resources/' + FILE_NAME

    static final String PROBE_ALIAS = 'gnomish-probe'

    /** What {@code git gnomish-probe} prints when the file is in force. */
    static final String PROBE_LINE = 'gnomish-adversarial-gitconfig-in-effect'

    /** The host whose URLs the file rewrites onto {@code ext::}; no real remote is ever rewritten. */
    static final String REWRITE_SENTINEL = 'https://gnomish-rewrite.invalid/'

    /** Left in git's working directory when the {@code ext::} rewrite ran. */
    static final String EXT_RAN_MARKER = '.gnomish-ext-ran'

    /** Left in git's working directory when the credential helper was asked to {@code get}. */
    static final String CREDENTIAL_GET_MARKER = '.gnomish-credential-get'

    /** The committer identity the file supplies in place of the operator's own. */
    static final String IDENTITY_NAME = 'gnomish-factory test'

    static final String IDENTITY_EMAIL = 'test@gnomish-factory.invalid'

    /** Every key the file defines, as {@code git config --list} spells them. */
    static final Set<String> KEYS = [
        'alias.' + PROBE_ALIAS,
        'submodule.recurse',
        'fetch.prune',
        "url.ext::touch ${EXT_RAN_MARKER} .insteadof".toString(),
        'credential.helper',
        'user.name',
        'user.email',
    ] as Set

    private AdversarialGitConfig() {
    }

    /** The file the build handed git, resolved from the environment; fails if the build did not. */
    static Path path() {
        String value = System.getenv(VARIABLE)
        assert value: "${VARIABLE} is not set: the test build points it at ${RELATIVE_PATH}"
        Path path = Path.of(value)
        assert path.fileName.toString() == FILE_NAME: "${VARIABLE} names ${value}, not ${RELATIVE_PATH}"
        assert Files.isRegularFile(path): "${VARIABLE} points at a missing file: ${value}"
        path
    }

    /** The committed text, read from the classpath copy of the file. */
    static String contents() {
        InputStream stream = AdversarialGitConfig.getResourceAsStream('/' + FILE_NAME)
        assert stream != null: "${FILE_NAME} is missing from the test-fixtures resources"
        stream.withCloseable {
            new String(it.readAllBytes(), StandardCharsets.UTF_8)
        }
    }

    /**
     * Runs the probe through {@code runner} and fails unless the file answers — the first line
     * of every spec that asserts isolation from the operator's git configuration, so a run whose
     * git does not see the file (outside Gradle, or through a stand-in that redirects the
     * variable) reports "not in force" instead of a vacuous pass.
     */
    static void assertInEffect(GitProcessRunner runner) {
        Path cwd = Path.of(System.getProperty('user.dir'))
        GitCommandResult result = runner.run(cwd, PROBE_ALIAS)
        String answer = result.stdout().forParsing().trim()
        assert result.exitCode() == 0 && answer == PROBE_LINE:
        "the adversarial global git configuration is not in force through this runner: " +
        "expected `git ${PROBE_ALIAS}` to print `${PROBE_LINE}`, got exit ${result.exitCode()} " +
        "and `${answer}`. The test build sets ${VARIABLE} to ${RELATIVE_PATH}; run under Gradle."
    }
}
