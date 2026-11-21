package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.agent.DecisionRequestRead
import com.github.oinsio.gnomish.app.port.git.PendingVerification
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.executorcontract.ResultSource
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR21 of add-sandbox-core (design D15), the parsing edge of the snapshot
 * message contract: only a well-formed {@code gnomish: snapshot <stage>#<round> <token>}
 * subject at the branch tip classifies as an interrupted verification — a
 * subject with an empty stage, a missing round marker, a non-numeric round or
 * no readable round token never does, so resume falls back to the ordinary
 * salvage path instead of re-running verification against a commit that is not
 * a factory snapshot.
 *
 * <p>FR15, NFR-R4 of make-checkpoint-gate-durable (design D10): the token names the round, and the
 * request the round asked — if any — is read from the snapshot's tree at exactly that round's path.
 */
class SnapshotTipCheckSpec extends Specification implements BareGitRepoFixture, RegisteredBranchFixture {

    static final String TASK = 'TIP-1'
    static final String BRANCH = 'gnomish/TIP-1'

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path clone

    def setup() {
        clone = initWorkingRepo(tempDir, 'clone')
        new File(clone.toFile(), 'a.txt').text = 'seed'
        commitAll(clone)
        gitOutput(clone, 'checkout', '-b', BRANCH)
    }

    /** A factory closing commit with {@code subject}, registered as the closing push registers it. */
    private void tipWithSubject(String subject) {
        gnomeCommit(subject)
        registerTip(clone, TASK)
    }

    /** A commit the gnome makes: the branch moves, the register does not. */
    private void gnomeCommit(String subject) {
        new File(clone.toFile(), 'a.txt').text = subject
        commitAll(clone, subject)
    }

    private String tip() {
        gitOutput(clone, 'rev-parse', 'HEAD')
    }

    /** Lands a file at {@code path} in a commit with {@code subject}, as the in-box snapshot does. */
    private void commitFile(String path, String content, String subject) {
        Path file = clone.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        commitAll(clone, subject)
        registerTip(clone, TASK)
    }

    def "FR15, FR13: a well-formed subject yields the attempt commit, stage, round, the typed token and no request"() {
        given: 'a round opened on the current tip and closed without asking'
        def token = RoundToken.of(tip())
        tipWithSubject(ServiceCommitMessages.snapshot('implement', 3, token))

        when:
        def pending = new SnapshotTipCheck(runner, clone).inspect(TASK)

        then:
        with(pending.get()) {
            attemptCommit() == tip()
            stage() == 'implement'
            round() == 3
            request().isEmpty()
        }

        and: 'FR13 (design D10 as amended): the recorded token travels on, typed — the round it names, not the snapshot'
        pending.get().token() == token
        pending.get().token() != RoundToken.of(pending.get().attemptCommit())
    }

    // FR19 of migrate-verify-to-executor-contract (design D22): a round its executor did not
    // complete closes under the unverified subject, which no pickup resumes as a verification
    def "FR19: a registered unverified snapshot is not an interrupted verification"() {
        given: 'a round opened on the current tip, closed unverifiable, and registered'
        def token = RoundToken.of(tip())
        tipWithSubject(ServiceCommitMessages.unverifiedSnapshot('implement', 3, token))

        expect:
        new SnapshotTipCheck(runner, clone).inspect(TASK).isEmpty()
    }

    /** A factory commit on the branch with {@code subject} and the given parents, registered. */
    private String registeredCommitOn(String subject, String... parents) {
        def args = [
            'commit-tree',
            tip() + '^{tree}'
        ]
        parents.each { args += ['-p', it] }
        def commit = gitOutput(clone, *(args + ['-m', subject]))
        gitOutput(clone, 'update-ref', 'refs/heads/' + BRANCH, commit)
        registerTip(clone, TASK)
        commit
    }

    // FR3, FR20 of migrate-verify-to-executor-contract (design D21): a registered pending commit is
    // the same interrupted verification one step later — the checks judge the snapshot below it,
    // the round's close sits on the pending commit
    def "FR20: a registered pending commit yields the snapshot below it and itself as the registered commit"() {
        given: 'a round snapshotted, then a check of it went pending'
        def token = RoundToken.of(tip())
        tipWithSubject(ServiceCommitMessages.snapshot('implement', 3, token))
        def snapshot = tip()
        def pending = registeredCommitOn(ServiceCommitMessages.pending('implement', 3, token), snapshot)

        when:
        def found = new SnapshotTipCheck(runner, clone).inspect(TASK)

        then:
        found.get() == new PendingVerification(snapshot, 'implement', 3, token, Optional.empty(), pending, [pending, snapshot])
    }

    def "FR20: a registered snapshot is its own registered commit"() {
        given:
        def token = RoundToken.of(tip())
        tipWithSubject(ServiceCommitMessages.snapshot('implement', 3, token))

        expect:
        new SnapshotTipCheck(runner, clone).inspect(TASK).get().registered() == tip()
        new SnapshotTipCheck(runner, clone).inspect(TASK).get().chain() == [tip()]
    }

    // D21: a second check's pending commit stacks on the first one, so the walk crosses both
    def "FR20: a stacked pending commit is followed over the round's pending commits down to the snapshot"() {
        given:
        def token = RoundToken.of(tip())
        tipWithSubject(ServiceCommitMessages.snapshot('implement', 3, token))
        def snapshot = tip()
        def first = registeredCommitOn(ServiceCommitMessages.pending('implement', 3, token), snapshot)
        def second = registeredCommitOn(ServiceCommitMessages.pending('implement', 3, token), first)

        when:
        def found = new SnapshotTipCheck(runner, clone).inspect(TASK).get()

        then:
        found.attemptCommit() == snapshot
        found.registered() == second
        found.token() == token

        and: 'the chain the one walk passed is the first-parent history from the register down to the snapshot (D14)'
        found.chain() == [second, first, snapshot]
        def history = gitOutput(clone, 'rev-list', '--first-parent', second).readLines()
        found.chain() == history.subList(0, history.indexOf(snapshot) + 1)
    }

    def "FR15, FR20: the request a snapshot carries is read through the pending commit above it"() {
        given:
        def token = RoundToken.of(tip())
        def path = EnvelopePaths.answerFile(new AttemptKey(TASK, 'implement', 0), token).relative()
        commitFile(path, '{"question":"which db?"}', ServiceCommitMessages.snapshot('implement', 0, token))
        registeredCommitOn(ServiceCommitMessages.pending('implement', 0, token), tip())

        expect:
        new SnapshotTipCheck(runner, clone).inspect(TASK).get().request().get().question().forLog() == 'which db?'
    }

    // D21: only the round's own commits are crossed — a commit of another identity, another kind or
    // another shape below a pending commit ends the walk with nothing, and the pickup salvages
    def "FR20: a pending commit whose parent is not a snapshot of its round is refused (#variant)"() {
        given:
        def token = RoundToken.of(tip())
        def other = RoundToken.of(gitOutput(clone, 'rev-parse', 'HEAD^{tree}'))
        Map<String, String> below = [
            'another token': ServiceCommitMessages.snapshot('implement', 3, other),
            'another round': ServiceCommitMessages.snapshot('implement', 2, token),
            'another stage': ServiceCommitMessages.snapshot('review', 3, token),
            'an unverified snapshot': ServiceCommitMessages.unverifiedSnapshot('implement', 3, token),
            'a round commit': 'gnomish: round implement#3',
            'a pending commit of another token': ServiceCommitMessages.pending('implement', 3, other),
        ]
        tipWithSubject(below[variant])
        registeredCommitOn(ServiceCommitMessages.pending('implement', 3, token), tip())
        def logs = LogCaptureSupport.attach(PendingChain, Level.DEBUG)

        when:
        def found = new SnapshotTipCheck(runner, clone).inspect(TASK)
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        found.isEmpty()
        events*.formattedMessage.any {
            it.contains('stands on no snapshot of its round')
        }

        where:
        variant << [
            'another token',
            'another round',
            'another stage',
            'an unverified snapshot',
            'a round commit',
            'a pending commit of another token'
        ]
    }

    // D21: the walk crosses only the round's pending commits — a foreign commit between the pending
    // commit and the round's own snapshot ends it, even though the snapshot lies below
    def "FR20: a foreign commit between the pending commit and its round's snapshot is not crossed"() {
        given:
        def token = RoundToken.of(tip())
        tipWithSubject(ServiceCommitMessages.snapshot('implement', 3, token))
        gnomeCommit('wip: between')
        registeredCommitOn(ServiceCommitMessages.pending('implement', 3, token), tip())

        expect:
        new SnapshotTipCheck(runner, clone).inspect(TASK).isEmpty()
    }

    def "FR20: a pending commit with #count parents is not the factory's and is refused"() {
        given:
        def token = RoundToken.of(tip())
        tipWithSubject(ServiceCommitMessages.snapshot('implement', 3, token))
        def snapshot = tip()
        def parents = count == 0 ? [] : [
            snapshot,
            gitOutput(clone, 'rev-parse', snapshot + '^')
        ]
        registeredCommitOn(ServiceCommitMessages.pending('implement', 3, token), *parents)

        expect:
        new SnapshotTipCheck(runner, clone).inspect(TASK).isEmpty()

        where:
        count << [0, 2]
    }

    def "FR20: a pending subject without a readable round is traced as malformed and refused"() {
        given:
        tipWithSubject('gnomish: pending implement#latest 0a1b')
        def logs = LogCaptureSupport.attach(SnapshotTipCheck, Level.DEBUG)

        when:
        def found = new SnapshotTipCheck(runner, clone).inspect(TASK)
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        found.isEmpty()
        events.size() == 1
        events[0].formattedMessage.contains('unreadable stage#round token')
    }

    def "FR15: the request the round asked is read from the snapshot's tree at its token path"() {
        given:
        def token = RoundToken.of(tip())
        def path = EnvelopePaths.answerFile(new AttemptKey(TASK, 'implement', 0), token).relative()
        commitFile(path, '{"question":"which db?"}', ServiceCommitMessages.snapshot('implement', 0, token))

        and: 'the working copy no longer holds it — the read is of the snapshot, not of a working copy'
        Files.delete(clone.resolve(path))

        expect:
        new SnapshotTipCheck(runner, clone).inspect(TASK).get().request().get().question().forLog() == 'which db?'
    }

    // FR6 of define-executor-contract; crash-consistency item 14: recovery parses the request
    //     through the live path's read, so a resumed round asks exactly what the live one would.
    def "FR6: the recovered request is what the live read makes of the same bytes, fallbacks included"() {
        given: 'a round that wrote garbage instead of decision JSON, and died after its snapshot'
        def token = RoundToken.of(tip())
        def path = EnvelopePaths.answerFile(new AttemptKey(TASK, 'implement', 0), token).relative()
        def garbage = 'not json, the agent rambled'
        commitFile(path, garbage, ServiceCommitMessages.snapshot('implement', 0, token))

        when:
        def logs = LogCaptureSupport.attach(DecisionRequestRead)
        def recovered = new SnapshotTipCheck(runner, clone).inspect(TASK).get().request()
        def live = DecisionRequestRead.read({ long limit ->
            Optional.of(garbage.getBytes(StandardCharsets.UTF_8))
        } as ResultSource)
        logs.detach()

        then:
        recovered == live
        recovered.get().question().forLog() == garbage
        recovered.get().options().isEmpty()
    }

    def "NFR-R4: a request at the same key under another round's token is not read"() {
        given: 'an earlier round left its request on the branch'
        def earlier = RoundToken.of(tip())
        commitFile(EnvelopePaths.answerFile(new AttemptKey(TASK, 'implement', 0), earlier).relative(),
                '{"question":"stale?"}', 'gnomish: task approved')

        and: 'the current round, opened on the next tip, asked nothing'
        tipWithSubject(ServiceCommitMessages.snapshot('implement', 0, RoundToken.of(tip())))

        expect:
        new SnapshotTipCheck(runner, clone).inspect(TASK).get().request().isEmpty()
    }

    def "FR21, FR15: a malformed snapshot subject never classifies as an interrupted verification"() {
        given: 'a tip whose subject only imitates the snapshot message shape'
        tipWithSubject(subject)
        def logs = LogCaptureSupport.attach(SnapshotTipCheck, Level.DEBUG)

        when:
        def pending = new SnapshotTipCheck(runner, clone).inspect(TASK)
        def events = List.copyOf(logs.list)
        logs.detach()

        then: 'no pending verification — the resume goes through salvage'
        pending.isEmpty()

        and: 'FR5 of harden-logging-observability: the factory wrote this tip, so the anomaly is traced'
        events.size() == 1
        events[0].level == Level.DEBUG
        events[0].formattedMessage.contains('unreadable stage#round token')

        where:
        subject << [
            'gnomish: snapshot #3 0a1b',
            'gnomish: snapshot implement 0a1b',
            'gnomish: snapshot implement#latest 0a1b',
            // The token-less subject of the earlier contract: no round to read the request of.
            'gnomish: snapshot implement#1',
            'gnomish: snapshot implement#1 ',
            'gnomish: snapshot implement#1 NOT-HEX',
            // The shape matches, the values do not: a blank stage, a round past int range.
            'gnomish: snapshot  #1 0a1b',
            'gnomish: snapshot implement#99999999999 0a1b',
        ]
    }

    def "an ordinary tip is not a snapshot and is not traced as an anomaly"() {
        given:
        tipWithSubject('gnomish: round implement#1')
        def logs = LogCaptureSupport.attach(SnapshotTipCheck, Level.DEBUG)

        when:
        def pending = new SnapshotTipCheck(runner, clone).inspect(TASK)
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        pending.isEmpty()
        events.isEmpty()
    }

    // FR5: a tip read git refused routes the resume through salvage exactly as an ordinary tip
    // does — the DEBUG line is the only thing that distinguishes the two afterwards.
    def "FR5: a tip read git refuses is traced before it reads as 'not a snapshot'"() {
        given:
        def logs = LogCaptureSupport.attach(SnapshotTipCheck, Level.DEBUG)

        when:
        def pending = new SnapshotTipCheck(runner, clone).inspect('NO-SUCH-BRANCH')
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        pending.isEmpty()

        and:
        events.size() == 1
        events[0].level == Level.DEBUG
        events[0].formattedMessage.contains('no factory register for gnomish/NO-SUCH-BRANCH')
    }

    // FR6: git's stderr is text from outside this process; one refused read stays one line.
    def "FR6: a malformed subject cannot forge a second log line"() {
        given:
        tipWithSubject('gnomish: snapshot impl\u001b[31m#not-a-number')
        def logs = LogCaptureSupport.attach(SnapshotTipCheck, Level.DEBUG)

        when:
        new SnapshotTipCheck(runner, clone).inspect(TASK)
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        events.size() == 1
        !events[0].formattedMessage.contains('\u001b')
    }
}
