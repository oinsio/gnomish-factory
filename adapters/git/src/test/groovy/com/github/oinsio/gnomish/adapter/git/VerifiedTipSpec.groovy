package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException
import com.github.oinsio.gnomish.subprocess.Termination
import com.github.oinsio.gnomish.untrustedtext.TextSafety
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13 of harden-logging-observability: a tip resolution answers with a SHA and nothing else, so
 * every failure shape reduces to the empty string unless the invocation itself is checked. This is
 * the one place that check lives — {@link VerifiedTip#read} for probes that may skip an
 * observation, {@link VerifiedTip#required} for resolutions recorded durably or gating a
 * decision. M6's failing-invocation spec for the shared resolution point both refusal paths
 * reduce to.
 */
class VerifiedTipSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    static final String SHA = '1f2e3d4c5b6a798877665544332211000ffeeddc'

    def "FR13: only a zero-exit resolution that printed a ref is a tip"() {
        expect:
        VerifiedTip.read(result) == expected

        where:
        result || expected
        GitCommandResult.of(0, SHA + '\n', '') || Optional.of(SHA)
        GitCommandResult.of(128, '', 'fatal: bad revision') || Optional.empty()
        GitCommandResult.of(0, '', '') || Optional.empty()
        GitCommandResult.of(0, '   \n', '') || Optional.empty()
        GitCommandResult.of(0, SHA, '', Termination.INTERRUPTED) || Optional.empty()
    }

    // Scenario: git/git-task-persistence / A blank tip is never recorded
    def "FR13: a durable resolution refuses a blank tip and carries the git evidence"() {
        when:
        VerifiedTip.required('refs/heads/gnomish/PROJ-1', 'rev-parse', GitCommandResult.of(128, '', 'fatal: bad revision'))

        then:
        def failure = thrown(BranchTipUnavailableException)
        failure.message.contains('refs/heads/gnomish/PROJ-1')
        failure.message.contains('exited 128')
        failure.message.contains('fatal: bad revision')
    }

    def "FR13: a resolution that never ran to its own exit reports how it ended instead"() {
        when:
        VerifiedTip.required('HEAD', 'rev-parse', GitCommandResult.of(0, '', '', Termination.INTERRUPTED))

        then:
        def failure = thrown(BranchTipUnavailableException)
        failure.message.contains('did not run to its own exit')
        failure.message.contains('INTERRUPTED')
    }

    def "FR13: a clean resolution returns the trimmed sha"() {
        expect:
        VerifiedTip.required('HEAD', 'rev-parse', GitCommandResult.of(0, SHA + '\n', '')) == SHA
    }

    // FR6: git's stderr is subprocess output, and this message is rendered into a log record when
    //     the refusal is logged; a newline in it would forge a second record. logging.md puts that
    //     obligation on the throw site, since the log-call gate cannot see inside a message.
    def "FR6: the git evidence carried into the refusal message is sanitized at construction"() {
        given:
        def esc = Character.toString(27)
        def stderr = "fatal: bad revision\n2026-01-01 ERROR forged record${esc}[31m"

        when:
        VerifiedTip.required('refs/heads/gnomish/PROJ-1', 'rev-parse', GitCommandResult.of(128, '', stderr))

        then:
        def failure = thrown(BranchTipUnavailableException)
        !failure.message.contains('\n')
        !failure.message.contains(esc)
        failure.message.contains('forged record')
    }

    // FR3, FR10 of type-untrusted-text: the commit-id reader is a parser — it reads git's stdout
    //     through the parsing accessor, uncapped and unflattened, and yields a string that is no
    //     longer a carrier. Driven on a real repository so the bytes are the ones git wrote.
    // Scenario: core/untrusted-text / A parser reads the captured bytes and yields a value
    def "FR3: the commit-id reader is a parser and yields exactly the id git printed"() {
        given: 'a repository and the answer git gives to a revision resolution'
        Path repo = initWorkingRepo(tempDir, 'repo')
        Files.writeString(repo.resolve('a.txt'), 'first')
        commitAll(repo)
        def resolution = new GitProcessRunner().run(repo, 'rev-parse', 'HEAD')

        expect: 'the reader is marked as a parser'
        VerifiedTip.isAnnotationPresent(UntrustedParser)

        and: 'the value is the id git wrote, without its line end — a commit id, not a carrier'
        VerifiedTip.required('HEAD', 'rev-parse', resolution) == gitOutput(repo, 'rev-parse', 'HEAD')
        VerifiedTip.required('HEAD', 'rev-parse', resolution) ==~ /[0-9a-f]{40}/
    }

    // F105: the parser states what makes its string inert — the fixed shape of a full object id,
    //     40 (SHA-1) or 64 (SHA-256) lowercase hex characters — and applies it: anything else a
    //     zero-exit resolution printed is no tip, exactly as a blank answer is no tip.
    // Scenario: core/untrusted-text / A parser yields a value that is no longer untrusted text
    def "F105: a printed answer that is not a full object id is no tip"() {
        expect:
        VerifiedTip.read(GitCommandResult.of(0, printed + '\n', '')) == Optional.empty()

        where:
        printed << [
            'HEAD',
            SHA.substring(0, 7),
            SHA.substring(0, 39),
            SHA + '0',
            SHA.toUpperCase(),
            SHA.substring(0, 39) + 'g',
            SHA + '\n' + SHA,
            SHA + ' extra',
            '-' + SHA.substring(1),
            'a' * 63,
            'a' * 65,
        ]
    }

    def "F105: a SHA-256 object id is a tip like a SHA-1 one"() {
        given:
        def sha256 = '0123456789abcdef' * 4

        expect:
        VerifiedTip.read(GitCommandResult.of(0, sha256 + '\n', '')) == Optional.of(sha256)
    }

    def "F105: a durable resolution refuses an answer that is not an object id and carries the git evidence"() {
        when:
        VerifiedTip.required('refs/heads/gnomish/PROJ-1', 'rev-parse', GitCommandResult.of(0, 'not-a-sha\n', 'warning: odd'))

        then:
        def failure = thrown(BranchTipUnavailableException)
        failure.message.contains('refs/heads/gnomish/PROJ-1')
        failure.message.contains('exited 0')
        failure.message.contains('warning: odd')
    }

    def "FR3: the parser reads past the log exit's cap: what it parses is not the capped rendering"() {
        given: 'standard output longer than any log record may be, opening with a well-formed id'
        def printed = SHA + ('f' * (TextSafety.DEFAULT_CAP_CHARS * 2))

        expect: 'the whole printed text is checked, so the over-long answer is no tip'
        VerifiedTip.read(GitCommandResult.of(0, printed + '\n', '')) == Optional.empty()
    }
}
