package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.BranchAdoption
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR8 of fix-terminal-receipt-convergence (design D10): a fresh claim takes its branch from the
 * adoption owner too — {@link GitFreshTaskSupport#createdBranch} asks the owner over the location
 * of the branch the creation just made, so no fresh path builds the typed value itself.
 */
class FreshBranchAdoptionSpec extends Specification {

    static final Path CLONE_DIR = Path.of('/tmp/gnomish-clone')
    static final BranchLocation LOCAL = new BranchLocation.Local('refs/heads/gnomish/PROJ-1')

    TaskBranchGit branches = Mock(TaskBranchGit)

    def "FR8: the created branch is the owner's answer over its location"() {
        when:
        def branch = GitFreshTaskSupport.createdBranch(branches, CLONE_DIR, 'PROJ-1')

        then:
        1 * branches.locate(CLONE_DIR, 'PROJ-1') >> LOCAL

        then:
        1 * branches.adopt(CLONE_DIR, 'PROJ-1', LOCAL) >> new BranchAdoption.AlreadyLocal('PROJ-1')

        and:
        branch == new BranchAdoption.AlreadyLocal('PROJ-1')
    }

    def "FR8: a created branch the owner finds nowhere is an invariant violation, not a fresh start"() {
        given:
        branches.locate(CLONE_DIR, 'PROJ-1') >> new BranchLocation.NotFound()
        branches.adopt(CLONE_DIR, 'PROJ-1', _) >> new BranchAdoption.Nowhere()

        when:
        GitFreshTaskSupport.createdBranch(branches, CLONE_DIR, 'PROJ-1')

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains('"PROJ-1"')
        ex.message.contains('right after its creation')
    }
}
