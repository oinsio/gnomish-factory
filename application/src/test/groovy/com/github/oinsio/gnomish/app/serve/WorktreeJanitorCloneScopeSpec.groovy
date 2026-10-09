package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.project.CloneName
import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.app.project.ProjectName
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR9, NFR-R2 of add-project-registry: the worktree janitor sweeps only its own registered clone's
 * worktree folder — never a sibling clone's, of the same project or another — so two clones
 * served side by side cannot dispose each other's worktrees.
 */
class WorktreeJanitorCloneScopeSpec extends Specification {

    private static final Duration AGE_THRESHOLD = Duration.ofDays(14)
    private static final Instant NOW = Instant.parse('2026-09-30T00:00:00Z')
    private static final Instant AGED = NOW - AGE_THRESHOLD - Duration.ofDays(1)

    @TempDir
    Path tempDir

    List<String> disposed = []

    private RegisteredClone clone(String project, String name) {
        def projectName = new ProjectName(project)
        new RegisteredClone(projectName, new CloneName(name), tempDir.resolve(project).resolve(name),
                FactoryHome.at(tempDir.resolve('home')).project(projectName))
    }

    private WorktreeJanitor janitorFor(RegisteredClone clone) {
        new WorktreeJanitor(clone, AGE_THRESHOLD, { String key ->
            disposed << key
        } as TaskEnvironmentDisposal,
        { -> NOW } as InstantSource, Mock(Sleeper), { -> Set.of() })
    }

    private static Path agedWorktree(Path folder) {
        Path dir = Files.createDirectories(folder)
        Files.setLastModifiedTime(dir, FileTime.from(AGED))
        dir
    }

    def "FR9, NFR-R2 of add-project-registry: the janitor stays in its clone's folder"() {
        given: 'project widgets registers clones widgets and widgets-demo, each with an aged worktree'
        def widgets = clone('widgets', 'widgets')
        def demo = clone('widgets', 'widgets-demo')
        agedWorktree(widgets.worktrees().resolve('TASK-1'))
        def demoWorktree = agedWorktree(demo.worktrees().resolve('TASK-2'))

        when: 'the janitor runs for clone widgets'
        janitorFor(widgets).tick()

        then: 'it inspects only projects/widgets/worktrees/widgets/'
        widgets.worktrees() == tempDir.resolve('home/projects/widgets/worktrees/widgets')
        disposed == ['TASK-1']

        and: "widgets-demo's folder is left untouched"
        Files.isDirectory(demoWorktree)
    }

    def "FR9 of add-project-registry: a same-named clone of another project is not swept"() {
        given: 'clone api of billing and clone api of gateway, each with an aged worktree'
        def billing = clone('billing', 'api')
        def gateway = clone('gateway', 'api')
        agedWorktree(billing.worktrees().resolve('TASK-1'))
        agedWorktree(gateway.worktrees().resolve('TASK-9'))

        when:
        janitorFor(billing).tick()

        then:
        disposed == ['TASK-1']
    }
}
