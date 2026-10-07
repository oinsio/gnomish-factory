package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.RoundToken
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR16 of make-checkpoint-gate-durable (design D10): the harvested boundary carve-out names the
 * round token's path and nothing else under {@code .gnomish-task/}. The cannot-verify outcome is
 * pinned by {@code BoundaryProbeCannotVerifySpec}; the full round by {@code
 * EnvironmentRoundProtocolSpec}.
 */
class HarvestedBoundaryCheckSpec extends Specification implements BareGitRepoFixture {

    static final AttemptKey KEY = new AttemptKey('PROJ-1', 'implement', 0)
    static final RoundToken OTHER = RoundToken.of('ffffffffffffffffffffffffffffffffffffffff')

    @TempDir
    Path tempDir

    Path repo

    def setup() {
        repo = initWorkingRepo(tempDir)
        new File(repo.toFile(), 'a.txt').text = 'first'
        commitAll(repo)
    }

    private String head() {
        gitOutput(repo, 'rev-parse', 'HEAD')
    }

    private String gnomeWrites(String relativePath) {
        def file = new File(repo.toFile(), relativePath)
        file.parentFile.mkdirs()
        file.text = '{"question":"which db?"}'
        commitAll(repo, 'gnome round')
        head()
    }

    def "FR16: decisionPath spells the stage, the attempt and the round token"() {
        expect:
        HarvestedBoundaryCheck.decisionPath(KEY, OTHER) ==
                '.gnomish-task/decisions/implement-a0-ffffffffffffffffffffffffffffffffffffffff.json'
    }

    def "FR16: the current round's token path is the one permitted state-directory write"() {
        given:
        def openTip = head()
        def token = RoundToken.of(openTip)
        def snapshot = gnomeWrites(HarvestedBoundaryCheck.decisionPath(KEY, token))

        when:
        new HarvestedBoundaryCheck(new GitProcessRunner(), repo).verify('PROJ-1', openTip, snapshot, KEY, token)

        then:
        noExceptionThrown()
    }

    def "FR16: a same-key decision file under another token is a violation: #description"() {
        given:
        def openTip = head()
        def snapshot = gnomeWrites(path)

        when:
        new HarvestedBoundaryCheck(new GitProcessRunner(), repo)
                .verify('PROJ-1', openTip, snapshot, KEY, RoundToken.of(openTip))

        then:
        def violation = thrown(RoundBoundaryViolationException)
        violation.message.contains(path)

        where:
        description | path
        'another round\'s token' | HarvestedBoundaryCheck.decisionPath(KEY, OTHER)
        'the token-less name' | '.gnomish-task/decisions/implement-a0.json'
    }
}
