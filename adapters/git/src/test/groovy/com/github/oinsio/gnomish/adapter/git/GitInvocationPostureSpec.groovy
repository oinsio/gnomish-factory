package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.gittransfer.GitTransfer
import com.github.oinsio.gnomish.gittransfer.Refspec
import com.github.oinsio.gnomish.gittransfer.TransferSource
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR2, FR6, NFR-S2 of own-git-invocation-policy (design D3, D4) — three claims of the invocation
 * policy that hold per invocation rather than per clone, on real git through the production adapters
 * ({@link InvocationTaskFlow}): the clone's own maintenance settings start nothing, a hook path the
 * clone gains after the factory started runs nothing, and the operator's credential helper is still
 * consulted for origin, since the policy leaves credential keys ambient.
 *
 * <p>Each feature arms its posture and shows it armed, so none passes because git had nothing to do:
 * the maintenance feature runs the clone's own auto maintenance once the factory is done and sees it
 * pack, and the credential feature's origin answers every request with a challenge.
 */
class GitInvocationPostureSpec extends Specification implements BareGitRepoFixture {

    static final String BRANCH = InvocationTaskFlow.BRANCH

    /** Loose objects enough that every fan-out directory exceeds the threshold {@code gc.auto=1} sets. */
    static final int LOOSE_OBJECTS = 3000

    @TempDir
    Path tempDir

    final GitProcessRunner runner = new GitProcessRunner()
    Path clone
    InvocationTaskFlow flow

    def setup() {
        AdversarialGitConfig.assertInEffect(runner)
        clone = initWorkingRepo(tempDir, 'clone')
        commit(clone, 'a.txt', 'seed')
        addConvergedOrigin(clone, tempDir)
        flow = new InvocationTaskFlow(runner, RegisteredCloneFixture.registered(tempDir.resolve('home'), clone), tempDir)
    }

    def cleanup() {
        assertNothingPlantedRan(clone)
    }

    // Scenario: git/git-invocation-policy / Factory git starts no background maintenance
    def "FR1, FR2: commit objects, register moves and a fetch start no maintenance in a clone past the auto threshold"() {
        given: 'a clone that asks for gc on every auto maintenance, holding more loose objects than gc.auto=1 tolerates'
        FixtureGitConfig.set(clone, 'gc.auto', '1')
        FixtureGitConfig.set(clone, 'maintenance.gc.enabled', 'true')
        writeLooseObjects(clone)

        and: 'origin one commit ahead, so the fetch below delivers objects (git skips maintenance on an empty fetch)'
        String branch = currentBranch(clone)
        divergeFromOrigin(clone, tempDir)
        long looseBefore = looseObjects(clone)

        when: 'the factory writes commits by object, moves the register, and fetches from origin'
        flow.startTask()
        flow.persistRound('verify')
        def fetched = runner.run(clone,
                GitTransfer.fetch(TransferSource.ORIGIN, new Refspec("${branch}:refs/remotes/origin/${branch}")))

        then: 'every operation landed'
        fetched.exitCode() == 0
        flow.registeredCommit() == gitOutput(clone, 'rev-parse', BRANCH)

        and: 'no loose object was packed away: no maintenance or gc ran (the fetch may keep what it received as a pack)'
        long looseAfter = looseObjects(clone)
        looseAfter >= looseBefore

        when: "the clone's own auto maintenance, with the policy's gc threshold put back"
        gitOutput(clone, '-c', 'gc.auto=1', 'maintenance', 'run', '--auto')

        then: 'it packs the reachable loose objects: the threshold really was exceeded, so the quiet above was the policy'
        looseObjects(clone) <looseAfter
    }

    // Scenario: git/git-task-persistence / A hook path set in the clone after startup runs nothing
    def "FR6: a hook path pointed at executable hooks after the task started runs on no commit, ref update or worktree add"() {
        given: 'the factory has started the task'
        flow.startTask()

        and: 'only then the clone points core.hooksPath at a fresh directory of executable hooks'
        Path hooks = Files.createDirectories(tempDir.resolve('late-hooks'))
        List<Path> planted = [
            'pre-commit',
            'reference-transaction',
            'post-checkout'
        ].collect {
            StandIn.link(hooks.resolve(it), AdversarialLocalConfig.PRESET)
        }
        FixtureGitConfig.set(clone, 'core.hooksPath', hooks.toString())
        byte[] configBefore = Files.readAllBytes(clone.resolve('.git/config'))

        when: 'a round is persisted (commit-tree, register update-ref) and a worktree is added'
        flow.persistRound('verify')
        def added = runner.run(clone, 'worktree', 'add', '--detach', tempDir.resolve('late-worktree').toString(), BRANCH)

        then: 'every operation landed'
        added.exitCode() == 0
        flow.registeredCommit() == gitOutput(clone, 'rev-parse', BRANCH)

        and: 'no hook in the late directory ran, and the factory wrote nothing to the configuration'
        planted.findAll { Files.exists(StandIn.log(it)) } == []
        Files.readAllBytes(clone.resolve('.git/config')) == configBefore
    }

    // Scenario: git/git-invocation-policy / Operator credentials still reach origin
    def "NFR-S2: a push to an origin that asks for credentials consults the operator's credential helper"() {
        given: 'an origin that challenges every request for credentials'
        def challenging = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        challenging.createContext('/', { exchange ->
            exchange.responseHeaders.add('WWW-Authenticate', 'Basic realm="gnomish"')
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        } as HttpHandler)
        challenging.start()
        FixtureGitConfig.setRemoteUrl(clone, 'origin', "http://127.0.0.1:${challenging.address.port}/owner/repo.git")

        and: 'the task branch, and no trace yet of the helper'
        gitOutput(clone, 'branch', BRANCH)
        Path marker = clone.resolve(AdversarialGitConfig.CREDENTIAL_GET_MARKER)
        assert !Files.exists(marker)

        when:
        def pushed = new RefspecPush(runner).push(clone, BRANCH)

        then: "the helper the operator's global configuration names was asked for credentials"
        Files.exists(marker)

        and: 'it had none to give, so origin refused the push'
        pushed.exitCode() != 0

        cleanup:
        challenging.stop(0)
    }

    /** Writes {@link #LOOSE_OBJECTS} distinct blobs into the object store, loose, and removes their files. */
    private void writeLooseObjects(Path repo) {
        Path dir = Files.createDirectories(repo.resolve('loose'))
        List<String> paths = (1..LOOSE_OBJECTS).collect {
            Files.writeString(dir.resolve("${it}"), "loose blob ${it}\n")
            "loose/${it}".toString()
        }
        gitOutput(repo, (['hash-object', '-w'] + paths) as String[])
        dir.toFile().deleteDir()
    }

    /** The loose-object count {@code git count-objects} reports; gc lowers it by packing the reachable ones. */
    private long looseObjects(Path repo) {
        (gitOutput(repo, 'count-objects', '-v') =~ /(?m)^count: (\d+)$/).with {
            assert it.find(): 'count-objects reported no loose count'
            it.group(1) as long
        }
    }
}
