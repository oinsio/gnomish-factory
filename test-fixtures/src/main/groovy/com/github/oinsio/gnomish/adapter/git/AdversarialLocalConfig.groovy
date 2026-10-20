package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path

/**
 * The adversarial local-configuration posture of the shared clone fixture (design D6 of
 * own-git-invocation-policy): what {@link BareGitRepoFixture#initWorkingRepo} plants in every
 * working repository it builds, and the one reader of whether any planted program ran.
 *
 * <p>The plants, each a vector the factory's invocation policy ({@code GitInvocation}) must
 * neutralize:
 * <ul>
 *   <li>{@code core.hooksPath} naming a hook directory whose {@code pre-commit},
 *       {@code post-checkout} and {@code reference-transaction} hooks record their run and exit 1;</li>
 *   <li>{@code commit.gpgSign=true} with a {@code gpg.program} that records its run and exits 1;</li>
 *   <li>{@code core.fsmonitor} naming a command that records its run (and exits 1);</li>
 *   <li>{@code branch.autoSetupMerge=always}.</li>
 * </ul>
 * Every program is the committed stand-in preset {@code hook-record-refuse} reached through a
 * per-run link under {@code .git/gnomish-planted/} (ADR 0015): the preset records whatever argv it
 * is given (a hook's, {@code gpg}'s {@code --status-fd=2 -bsau <key>}, the fsmonitor hook protocol's
 * {@code <version> <token>}), so its marker is the link's own {@code <link>.log}, per repository.
 * The plants live inside the git directory, so no {@code add -A} ever stages them. A planted program
 * fails, so a git path that runs without the policy turns its spec red as well as leaving a marker.
 *
 * <p>The configuration writes go through {@link FixtureGitConfig}, since the factory runner refuses
 * them (FR5 of own-git-invocation-policy).
 *
 * <p>Implements FR8 of own-git-invocation-policy.
 */
final class AdversarialLocalConfig {

    /** The stand-in preset every planted program runs: record, then exit 1. */
    static final String PRESET = 'hook-record-refuse'

    /** The hooks planted in the hook directory, by the name git runs them by. */
    static final List<String> HOOKS = [
        'pre-commit',
        'post-checkout',
        'reference-transaction'
    ].asImmutable()

    private AdversarialLocalConfig() {}

    /** Plants the posture in {@code repo}, a fresh non-bare working repository. */
    static void plant(Path repo) {
        Path planted = Files.createDirectories(plantedDir(repo))
        Path hooks = Files.createDirectories(planted.resolve('hooks'))
        HOOKS.each { StandIn.link(hooks.resolve(it), PRESET) }
        StandIn.link(planted.resolve('gpg'), PRESET)
        StandIn.link(planted.resolve('fsmonitor'), PRESET)
        expectedConfig(repo).each { key, value ->
            FixtureGitConfig.set(repo, key, value)
        }
    }

    /**
     * The local configuration {@link #plant} writes in {@code repo}, key to value, in write order —
     * what a spec asserts is in force.
     */
    static Map<String, String> expectedConfig(Path repo) {
        Path planted = plantedDir(repo)
        [
            'core.hooksPath' : planted.resolve('hooks').toString(),
            'commit.gpgSign' : 'true',
            'gpg.program' : planted.resolve('gpg').toString(),
            'core.fsmonitor' : planted.resolve('fsmonitor').toString(),
            'branch.autoSetupMerge' : 'always'
        ]
    }

    /** Every planted program's link in {@code repo}, by name ({@code pre-commit}, {@code gpg}, …). */
    static Map<String, Path> programs(Path repo) {
        Path planted = plantedDir(repo)
        Map<String, Path> links = [:]
        HOOKS.each { links[it] = planted.resolve('hooks').resolve(it) }
        links.gpg = planted.resolve('gpg')
        links.fsmonitor = planted.resolve('fsmonitor')
        links
    }

    /** The names of the planted programs that ran in {@code repo}: those whose marker exists. */
    static List<String> ran(Path repo) {
        programs(repo).findAll { name, link ->
            Files.exists(StandIn.log(link))
        }.keySet().toList()
    }

    /** Fails, naming each program and its recorded argv, when any planted program ran in {@code repo}. */
    static void assertNothingRan(Path repo) {
        List<String> ran = ran(repo)
        assert ran.isEmpty(): "planted programs ran in ${repo}: " + ran.collect { name ->
            "${name}: ${Files.readAllLines(StandIn.log(programs(repo)[name])).find { it.startsWith('argv=') }}"
        }.join('; ')
    }

    private static Path plantedDir(Path repo) {
        repo.resolve('.git').resolve('gnomish-planted')
    }
}
