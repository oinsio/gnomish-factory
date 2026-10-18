package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR17 of fix-terminal-receipt-convergence (design D15 as amended 2026-10-17), the second layer: a
 * reader that turns git's answer into an object id asserts the id's frame — 40 or 64 lowercase hex
 * characters — so a prefix a holder's writes shortened or lengthened is refused on its own, whether
 * or not a drain was flagged. A command that exited zero and printed anything else is a visible
 * refusal ({@link BranchTipUnavailableException}), never the empty answer, which these readers
 * reserve for "no such ref" and which routes a take to a recreate or a delete. The empty arms
 * themselves are unchanged: a non-zero exit, and a blank {@code ls-remote}, still read as absent.
 *
 * <p>Driven through the runner's port ({@link ScriptedGit}).
 */
class ObjectIdAnswerSpec extends Specification {

    static final Path CLONE = Path.of('/clone')
    static final String ID = 'a' * 40
    static final String CUT = 'a' * 39

    def underTenure = { String taskId ->
        Optional.of(new ClaimEpoch(7L))
    } as ClaimEpochSource

    // Scenario: lifecycle/subprocess-supervision / A value reader asserts its frame
    def "FR17: a local branch tip that is not one full object id is refused: #answer"() {
        given:
        def git = new ScriptedGit().answer(['rev-parse'], 0, answer)

        when:
        new LocalBranchTip(git).read(CLONE, 'T-1')

        then:
        BranchTipUnavailableException refused = thrown()
        refused.message.contains('refs/heads/gnomish/T-1')

        where:
        answer << [
            CUT + '\n',
            ID + 'a\n',
            'g' * 40 + '\n',
            '\n'
        ]
    }

    def "FR17: a local branch tip read keeps its absent arm on a non-zero exit and its id on a full one"() {
        expect:
        new LocalBranchTip(new ScriptedGit().answer(['rev-parse'], 1)).read(CLONE, 'T-1').isEmpty()
        new LocalBranchTip(new ScriptedGit().answer(['rev-parse'], 0, ID + '\n')).read(CLONE, 'T-1') == Optional.of(ID)
    }

    def "FR17: the first field of an ls-remote line that is not one full object id is refused"() {
        given:
        def git = new ScriptedGit().answer(['ls-remote'], 0, CUT + '\trefs/heads/gnomish/T-1\n')

        when:
        new RemoteBranchTip(git).read(CLONE, 'T-1')

        then:
        BranchTipUnavailableException refused = thrown()
        refused.message.contains('ls-remote')
    }

    def "FR17: an ls-remote read keeps its absent arms on a blank answer and a non-zero exit, and its id on a full one"() {
        expect:
        new RemoteBranchTip(new ScriptedGit().answer(['ls-remote'], 0, '')).read(CLONE, 'T-1').isEmpty()
        new RemoteBranchTip(new ScriptedGit().answer(['ls-remote'], 128)).read(CLONE, 'T-1').isEmpty()
        new RemoteBranchTip(new ScriptedGit().answer(['ls-remote'], 0, ID + '\trefs/heads/gnomish/T-1\n'))
        .read(CLONE, 'T-1') == Optional.of(ID)
    }

    def "FR17: a replica pair whose local tip answer is not one full object id is refused, never reconciled"() {
        given: 'origin tracking answers a full id, the local branch a cut one'
        def git = ScriptedGit.reftableClone()
                .answer(['fetch'], 0)
                .answer(['update-ref'], 0)
                .answer(['merge-base'], 1)
                .answer([
                    'rev-parse',
                    '--verify',
                    '--quiet',
                    'refs/remotes/origin/gnomish/T-1'
                ], 0, 'b' * 40 + '\n')
                .answer(['rev-parse'], 0, CUT + '\n')
                .answer([], 0)

        when:
        ReplicaPairReconciler.forClone(git, CLONE, underTenure).reconcile('T-1', 'gnomish/T-1')

        then: 'nothing was swapped or discarded on the strength of a prefix'
        thrown(BranchTipUnavailableException)
        git.calls(['update-ref']).isEmpty()
    }

    def "FR17: a register answer that is not one full object id is refused, never read as no register: #which"() {
        given:
        def git = new ScriptedGit().answer(['rev-parse'], 0, CUT + '\n')

        when:
        read.call(new FactoryRegisterRead(git))

        then:
        thrown(BranchTipUnavailableException)

        where:
        which | read
        'registered' | { FactoryRegisterRead r ->
            r.registered(CLONE, 'T-1')
        }
        'completedBefore' | { FactoryRegisterRead r ->
            r.completedBefore(CLONE, new RegisteredCommit(ID))
        }
    }

    def "FR17: a register read keeps its absent arm on a non-zero exit"() {
        expect:
        new FactoryRegisterRead(new ScriptedGit().answer(['rev-parse'], 1)).registered(CLONE, 'T-1').isEmpty()
    }

    def "FR17: adopting origin's line refuses an origin register answer that is not one full object id"() {
        given:
        def git = new ScriptedGit().answer(['rev-parse'], 0, CUT + '\n').answer(['update-ref'], 0)

        when:
        new FactoryRegister(git).adopt(CLONE, 'T-1', 'refs/heads/gnomish/T-1', 'b' * 40, ID, null)

        then: 'no transaction was written with the register left out or set to a prefix'
        thrown(BranchTipUnavailableException)
        git.calls(['update-ref']).isEmpty()
    }

    def "FR17: a base refs line whose commit is not one full object id is unanswered, never a base or an absence"() {
        given:
        def git = new ScriptedGit().answer(['ls-remote'], 0, "${branchId}\trefs/heads/main\n${tagId}\trefs/tags/main\n")

        when:
        def held = new RemoteBaseRef(git).read(CLONE, 'main', branches, tags)

        then: 'the infrastructure arm, naming the ref it could not read'
        held instanceof RemoteBaseRef.Held.Unanswered
        (held as RemoteBaseRef.Held.Unanswered).reason().forLog().contains(named)

        where:
        branches | tags | branchId | tagId | named
        true | true | CUT | ID | 'refs/heads/main'
        true | false | CUT | ID | 'refs/heads/main'
        false | true | ID | CUT | 'refs/tags/main'
    }

    def "FR17: a base refs read with full object ids names them, and an unasked malformed namespace is not read"() {
        given:
        def git = new ScriptedGit().answer(['ls-remote'], 0, "${CUT}\trefs/heads/main\n${ID}\trefs/tags/main\n")

        expect:
        new RemoteBaseRef(git).read(CLONE, 'main', false, true) == new RemoteBaseRef.Held.Tag(ID)
    }
}
