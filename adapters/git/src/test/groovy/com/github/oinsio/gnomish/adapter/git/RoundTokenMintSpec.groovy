package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.domain.engine.RoundToken
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR21 of define-executor-contract, M1 of extract-agent-round-runner (design D7 as amended): the one
 * minting step both branch-mode round sources call — the tip the revision names, verified, parsed
 * through {@link RoundToken#of} and opened in the run's {@link CurrentRound}.
 */
class RoundTokenMintSpec extends Specification {

    static final Path REPOSITORY = Path.of('/clone')
    static final String TIP = '1f2e3d4c5b6a798877665544332211000ffeeddc'
    static final String NEXT = 'abcdefabcdefabcdefabcdefabcdefabcdefabcd'

    def "FR21: a fresh round opens in the cell on the tip the revision names, read in the given repository"() {
        given:
        def git = new ScriptedGit().answer([
            'rev-parse',
            'refs/heads/gnomish/PROJ-1'
        ], 0, TIP + '\n')
        def rounds = new CurrentRound()

        when:
        def token = new RoundTokenMint(git, rounds).open(REPOSITORY, 'refs/heads/gnomish/PROJ-1')

        then:
        token == RoundToken.of(TIP)
        rounds.opened() == token
        git.calls()*.cwd == [REPOSITORY]
    }

    def "FR21: each open replaces the previous round with the tip it opens on"() {
        given:
        def git = new ScriptedGit().answers(['rev-parse', 'HEAD'],
        GitCommandResult.of(0, TIP + '\n', ''), GitCommandResult.of(0, NEXT + '\n', ''))
        def rounds = new CurrentRound()
        def mint = new RoundTokenMint(git, rounds)
        mint.open(REPOSITORY, 'HEAD')

        when:
        def second = mint.open(REPOSITORY, 'HEAD')

        then:
        second == RoundToken.of(NEXT)
        rounds.opened() == second
    }

    def "FR21: a tip that does not resolve opens no round and names the revision"() {
        given:
        def git = new ScriptedGit().answer(['rev-parse', 'HEAD'], 128, '', 'fatal: bad revision\n')
        def rounds = new CurrentRound()

        when:
        new RoundTokenMint(git, rounds).open(REPOSITORY, 'HEAD')

        then:
        def failure = thrown(BranchTipUnavailableException)
        failure.message.contains('HEAD')

        when:
        rounds.opened()

        then:
        thrown(IllegalStateException)
    }
}
