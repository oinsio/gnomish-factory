package com.github.oinsio.gnomish.adapter.law

import static com.github.oinsio.gnomish.adapter.law.PipelineDefinitionFixtures.pipeline
import static com.github.oinsio.gnomish.adapter.law.PipelineDefinitionFixtures.stage

import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.gitobjects.GitObjectsFixture
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR11, D12 of add-base-ref-resolution: the git-objects twin of {@link PipelineLawReaderSpec}.
 * The same freeze contract — every stage's control file and every judge check's criteria file
 * captured once, a per-file failure captured as data rather than thrown — driven through
 * {@link GitObjectsLawSource} bound to one real commit instead of a working-tree directory.
 *
 * <p>What only this medium can state: the law is the law <em>commit's</em> tree, so neither a
 * later commit on the same branch nor an uncommitted edit in the clone reaches a running task,
 * and a symlink law file is refused outright — a git tree has no {@code realpath} to
 * canonicalize it against.
 */
class GitObjectsPipelineLawReaderSpec extends Specification implements GitObjectsFixture {

    @TempDir
    Path tempDir

    Path work
    Path bare

    def setup() {
        work = initWorkingRepo(tempDir, 'law-work')
        Files.createDirectories(work.resolve('.gnomish'))
        bare = initBareRepo(tempDir, 'origin.git')
        addRemote(work, 'origin', bare.toString())
    }

    def "FR11, FR19: freezes each stage's instructions and every judge check's criteria from the law commit"() {
        given:
        write('.gnomish/instructions.md', 'Do the thing.')
        write('.gnomish/criteria.md', 'The output must be idempotent.')
        def definition = pipeline(stage('instructions.md', [
            new VerifyCheck.Judge('criteria.md', 'claude-opus', [:], 1)
        ]))

        when:
        def law = PipelineLawReader.freeze(lawSource(commitAndPush('law')), definition)

        then:
        law.controlFile('instructions.md') == 'Do the thing.'
        law.controlFile('criteria.md') == 'The output must be idempotent.'
    }

    def "FR11, D12: a later commit on the base does not affect the law frozen at the law commit"() {
        given: 'the law commit, then a later commit rewriting the same file'
        write('.gnomish/instructions.md', 'Original instructions.')
        def lawCommit = commitAndPush('law')
        def definition = pipeline(stage('instructions.md', []))
        def law = PipelineLawReader.freeze(lawSource(lawCommit), definition)

        when:
        write('.gnomish/instructions.md', 'Later instructions.')
        commitAndPush('later')

        then: 'the running task still sees the law bound at invocation start'
        law.controlFile('instructions.md') == 'Original instructions.'
    }

    def "FR11, D12: an uncommitted edit in the clone's working tree is no part of the law"() {
        given:
        write('.gnomish/instructions.md', 'Committed instructions.')
        def lawCommit = commitAndPush('law')

        when: 'the gnome rewrites the file in the working tree, committing nothing'
        write('.gnomish/instructions.md', 'Uncommitted instructions the gnome planted.')
        def law = PipelineLawReader.freeze(lawSource(lawCommit), pipeline(stage('instructions.md', [])))

        then:
        law.controlFile('instructions.md') == 'Committed instructions.'
    }

    def "FR13, D14: a law file the law commit does not carry is captured unreadable and surfaces at use time"() {
        given:
        write('.gnomish/config.yaml', 'stages: []')
        def law = PipelineLawReader.freeze(lawSource(commitAndPush('law')), pipeline(stage('missing.md', [])))

        when:
        law.controlFile('missing.md')

        then: 'the captured reason names the missing law file and the commit it was looked for in'
        def e = thrown(UnreadableLawFileException)
        e.message.contains('missing.md')
        e.message.contains('.gnomish/missing.md')
    }

    def "NFR-S2: a law reference escaping the law root is captured unreadable, never read"() {
        given: 'a file outside the law root, committed alongside it'
        write('.gnomish/config.yaml', 'stages: []')
        write('secret.md', 'the secret')
        def law = PipelineLawReader.freeze(lawSource(commitAndPush('law')), pipeline(stage('../secret.md', [])))

        when:
        law.controlFile('../secret.md')

        then:
        def e = thrown(UnreadableLawFileException)
        e.message.contains('../secret.md')
        e.message.contains('escapes')
        !e.message.contains('the secret')
    }

    def "FR11, D12: a symlinked law file is refused fail-closed, never followed"() {
        given: 'a law reference that is a symlink to a file outside the law root'
        write('secret.md', 'the secret')
        Files.createSymbolicLink(work.resolve('.gnomish/instructions.md'), Path.of('../secret.md'))
        def law = PipelineLawReader.freeze(lawSource(commitAndPush('law')), pipeline(stage('instructions.md', [])))

        when:
        law.controlFile('instructions.md')

        then:
        def e = thrown(UnreadableLawFileException)
        e.message.contains('symlink')
        !e.message.contains('the secret')
    }

    private void write(String rel, String content) {
        Path target = work.resolve(rel)
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
    }

    /** Commits the working tree and publishes it as {@code refs/heads/base}; returns the commit's SHA. */
    private String commitAndPush(String message) {
        commitAll(work, message)
        gitOutput(work, 'push', '--force', 'origin', 'HEAD:refs/heads/base')
        gitOutput(work, 'rev-parse', 'HEAD')
    }

    private LawSource lawSource(String lawCommit) {
        GitObjects git = openGitObjects(bare, tempDir)
        new GitObjectsLawSource(git, git.resolveRef(lawCommit).get(), '.gnomish')
    }
}
