package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.port.contract.AttemptPersistenceContract
import java.nio.file.Path
import spock.lang.TempDir

/**
 * The production {@link GitAttemptPersistence} is a second concrete implementation
 * of {@link AttemptPersistenceContract}, subclassing the SAME suite the in-memory
 * adapter passes ({@code InMemoryAttemptPersistenceProductionContractSpec}): each
 * {@code persist()} call becomes one commit on the worktree's {@code HEAD}, and
 * {@link #retained} walks that commit history (oldest-first) to rebuild the
 * {@code (taskId, state, trace)} triple git durably retained for each round.
 *
 * <p>{@code threeRounds()} always uses stage {@code build} and rounds {@code 0,1,2}
 * with an empty {@link ToolTrace#calls()} per round, so the round's trace.jsonl blob
 * is always a present, zero-line file (see {@code TraceLineWriter} javadoc) —
 * reconstructing it needs no line-parsing, just {@code new ToolTrace(key, [])}.
 *
 * <p>Implements FR2 of add-git-workflow.
 */
class GitAttemptPersistenceProductionContractSpec extends AttemptPersistenceContract implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    private GitProcessRunner runner
    private Path repo

    @Override
    protected Optional<?> arrange() {
        runner = new GitProcessRunner()
        repo = initWorkingRepo(tempDir)
        new File(repo.toFile(), 'seed.txt').text = 'seed'
        runner.run(repo, 'add', 'seed.txt')
        runner.run(repo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        runner.run(repo, 'checkout', '-q', '-b', 'gnomish/TASK-1')
        Optional.of(new GitAttemptPersistence(runner, repo, 'TASK-1'))
    }

    @Override
    protected List<AttemptPersistenceContract.PersistedEntry> retained(Object adapter) {
        List<String> shas = runner.run(repo, 'log', '--reverse', '--format=%H').stdout().trim().readLines()
        // Drop the initial seed commit made in arrange(): only round commits remain.
        shas = shas.drop(1)

        shas.indexed().collect { int round, String sha ->
            def stateJson = runner.run(repo, 'show', "${sha}:.gnomish-task/state.json").stdout()
            def state = StateJsonMapper.fromDto(StateJsonMapper.readDto(stateJson))
            def key = new AttemptKey('TASK-1', 'build', round)
            new AttemptPersistenceContract.PersistedEntry('TASK-1', state, new ToolTrace(key, []))
        }
    }
}
