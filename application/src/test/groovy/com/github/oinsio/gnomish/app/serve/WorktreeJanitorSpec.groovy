package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.domain.engine.port.Clock
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import org.slf4j.MDC
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link WorktreeJanitor#tick}, task 7.1 of add-factory-serve (design D10, FR14): the age-based
 * disposal policy, driven directly with no thread and no real sleeping — {@code start}/{@code
 * loop}'s scheduling cadence is covered separately by {@link WorktreeJanitorLifecycleSpec}.
 *
 * <p>Implements FR14 of add-factory-serve (design D10).
 */
class WorktreeJanitorSpec extends Specification {

    private static final Duration AGE_THRESHOLD = Duration.ofDays(14)
    private static final Instant NOW = Instant.parse('2026-07-31T00:00:00Z')

    @TempDir
    Path tempDir

    Path cloneDir
    Path worktreesRoot
    List<String> disposed = []
    def disposal = { String key -> disposed << key } as TaskEnvironmentDisposal
    def clock = { -> NOW } as Clock
    def sleeper = Mock(Sleeper)

    def setup() {
        cloneDir = Files.createDirectory(tempDir.resolve('my-project'))
        worktreesRoot = Files.createDirectory(tempDir.resolve('worktrees'))
    }

    private WorktreeJanitor janitor(Set<TaskRef> held = Set.of()) {
        new WorktreeJanitor(worktreesRoot, cloneDir, AGE_THRESHOLD, disposal, clock, sleeper, {
            -> held
        })
    }

    private Path environment(String key, Instant lastActivity) {
        Path dir = Files.createDirectories(worktreesRoot.resolve('my-project').resolve(key))
        Path marker = dir.resolve('.gnomish-task').resolve('task.json')
        Files.createDirectories(marker.parent)
        Files.writeString(marker, '{}')
        Files.setLastModifiedTime(marker, FileTime.from(lastActivity))
        Files.setLastModifiedTime(dir, FileTime.from(lastActivity))
        dir
    }

    // FR14, D10: an environment past the age threshold, held by no slot of this instance, is
    //     disposed through the seam by its directory key.
    def "disposes an unheld environment older than the age threshold"() {
        given:
        environment('task-a', NOW - AGE_THRESHOLD - Duration.ofDays(1))

        when:
        janitor().tick()

        then:
        disposed == ['task-a']
    }

    // FR8, UX2 of harden-logging-observability: the janitor's loop belongs to no task, so its one
    //     per-task decision — and everything the disposal seam logs beneath it — names the task in
    //     the MDC. Without it a disposal is invisible to `grep taskId=<id>`, the filter an operator
    //     uses to ask what happened to a task.
    def "FR8: a disposal decision is findable by taskId, and its scope covers the disposal itself"() {
        given:
        environment('task-a', NOW - AGE_THRESHOLD - Duration.ofDays(1))
        def logs = LogCaptureSupport.attach(WorktreeJanitor)
        String scopeInsideDisposal = null
        def scopedDisposal = { String key ->
            scopeInsideDisposal = MDC.get('taskId')
            disposed << key
        } as TaskEnvironmentDisposal

        when:
        new WorktreeJanitor(worktreesRoot, cloneDir, AGE_THRESHOLD, scopedDisposal, clock, sleeper, {
            -> Set.of()
        }).tick()

        then:
        logs.list.find {
            it.formattedMessage.contains('disposing aged environment')
        }.MDCPropertyMap['taskId'] == 'task-a'

        and: 'the seam runs inside the same scope, so its own lines are findable too'
        scopeInsideDisposal == 'task-a'

        and: 'and the tick leaves nothing behind for the next environment it judges'
        MDC.get('taskId') == null

        cleanup:
        logs.detach()
        MDC.clear()
    }

    // FR14, D10: an environment younger than the threshold is left alone.
    def "keeps an unheld environment younger than the age threshold"() {
        given:
        environment('task-b', NOW - AGE_THRESHOLD + Duration.ofDays(1))

        when:
        janitor().tick()

        then:
        disposed.isEmpty()
    }

    // FR14, D10: age exactly equal to the threshold is already eligible ("at least this old", not
    //     strictly older) — pins the boundary comparison against a ConditionalsBoundaryMutator that
    //     would flip it to require strictly-greater-than.
    def "disposes an unheld environment aged exactly at the threshold"() {
        given:
        environment('task-exact', NOW - AGE_THRESHOLD)

        when:
        janitor().tick()

        then:
        disposed == ['task-exact']
    }

    // FR14, D10: a task this instance currently holds is never touched, regardless of age.
    def "never disposes an environment currently held by this instance"() {
        given:
        environment('task-c', NOW - AGE_THRESHOLD - Duration.ofDays(30))

        when:
        janitor(Set.of(new TaskRef('task-c'))).tick()

        then:
        disposed.isEmpty()
    }

    // FR14, D10: activity nested under .gnomish-task/ counts as the environment's last activity
    //     even when the top-level worktree directory's own timestamp is older.
    def "reads age from the most recently modified nested file, not the directory's own timestamp"() {
        given: 'the directory entry itself looks aged but a nested file was touched recently'
        Path dir = environment('task-d', NOW - AGE_THRESHOLD - Duration.ofDays(30))
        Path marker = dir.resolve('.gnomish-task').resolve('task.json')
        Files.setLastModifiedTime(marker, FileTime.from(NOW - Duration.ofDays(1)))

        when:
        janitor().tick()

        then:
        disposed.isEmpty()
    }

    // FR14, D10: an environment whose worktree directory contains no regular files anywhere (a
    //     newly created, never-touched directory) still gets a genuine last-activity instant from
    //     the directory's own timestamp — the fallback branch of lastActivity's orElseGet must
    //     return a real, usable Instant, not null, since the age comparison depends on it.
    def "reads age from the directory's own timestamp when it contains no regular files"() {
        given: 'an empty worktree directory (no nested files at all) aged past the threshold'
        Path dir = Files.createDirectories(worktreesRoot.resolve('my-project').resolve('task-empty'))
        Files.setLastModifiedTime(dir, FileTime.from(NOW - AGE_THRESHOLD - Duration.ofDays(1)))

        when:
        janitor().tick()

        then: 'the fallback instant was read and used, not null, so the aged empty directory is disposed'
        disposed == ['task-empty']
    }

    // FR14, D10: same fallback path, but the directory's own timestamp is fresh — proving the
    //     fallback instant genuinely participates in the keep/dispose decision rather than being
    //     ignored or always treated as "old enough".
    def "keeps an empty worktree directory whose own timestamp is fresh"() {
        given:
        Path dir = Files.createDirectories(worktreesRoot.resolve('my-project').resolve('task-empty-fresh'))
        Files.setLastModifiedTime(dir, FileTime.from(NOW - Duration.ofDays(1)))

        when:
        janitor().tick()

        then:
        disposed.isEmpty()
    }

    // FR14, D10: only directory entries under the project folder are candidate environments — a
    //     stray regular file sitting alongside the worktree directories (should never happen in
    //     practice, but the scan must not misidentify it) is excluded by the isDirectory filter,
    //     not swept up as if it were an environment.
    def "ignores a stray regular file in the project folder, even if it looks aged"() {
        given:
        environment('task-real', NOW - AGE_THRESHOLD - Duration.ofDays(1))
        Path stray = worktreesRoot.resolve('my-project').resolve('stray.txt')
        Files.writeString(stray, 'not a worktree')
        Files.setLastModifiedTime(stray, FileTime.from(NOW - AGE_THRESHOLD - Duration.ofDays(30)))

        when:
        janitor().tick()

        then: 'only the real, directory-shaped environment was disposed; the stray file was never a candidate'
        disposed == ['task-real']
    }

    // FR14, D10: a project folder that does not exist yet (a fresh worktreesRoot before any task
    //     ever ran) is a no-op, not an error.
    def "does nothing when the project has no worktrees yet"() {
        when:
        janitor().tick()

        then:
        disposed.isEmpty()
        0 * sleeper._
    }

    // task 2.5, add-serve-observability FR7: lastRunAt starts at construction time and is
    //     stamped from the injected clock on every completed tick, whether or not the project's
    //     worktree directory exists yet — the vitals reader must never see a stale placeholder.
    def "lastRunAt starts at construction time and advances with every completed tick, even with no worktrees yet"() {
        given:
        def instance = janitor()

        expect:
        instance.lastRunAt() == NOW

        when: 'a tick completes on a clock set one minute later'
        def laterClock = { -> NOW + Duration.ofMinutes(1) } as Clock
        def later = new WorktreeJanitor(worktreesRoot, cloneDir, AGE_THRESHOLD, disposal, laterClock, sleeper, {
            -> Set.of()
        })
        later.tick()

        then:
        later.lastRunAt() == NOW + Duration.ofMinutes(1)
    }

    // FR14, D10: several environments in one project folder are each judged independently.
    def "judges every environment in the project folder independently"() {
        given:
        environment('aged', NOW - AGE_THRESHOLD - Duration.ofDays(1))
        environment('fresh', NOW - Duration.ofDays(1))
        environment('held', NOW - AGE_THRESHOLD - Duration.ofDays(1))

        when:
        janitor(Set.of(new TaskRef('held'))).tick()

        then:
        disposed == ['aged']
    }
}
