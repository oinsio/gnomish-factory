package com.github.oinsio.gnomish.app.port.git

import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import java.util.function.UnaryOperator
import spock.lang.Specification

/**
 * FR1, FR3 of wire-host-mid-round-push (design D3): {@link TaskGit} carries the mid-round push
 * decoration as a fourth capability with an identity default — the convenience constructor keeps
 * every existing construction site untouched, and only the composition root supplies the real
 * operator.
 */
class TaskGitSpec extends Specification {

    def store = Stub(TaskStoreGit)
    def branches = Stub(TaskBranchGit)
    def worktrees = Stub(TaskWorktreeGit)

    // FR3: the three-component constructor defaults the decoration to identity, so a TaskGit
    // built without one decorates nothing — the source passes through unchanged.
    def "the convenience constructor defaults the mid-round push decoration to identity"() {
        given:
        def source = Stub(RoundEnvironmentSource)

        expect:
        new TaskGit(store, branches, worktrees).midRoundPush().apply(source).is(source)
    }

    // FR1: a supplied operator rides the bundle verbatim — what the composition root built is
    // exactly what the attachment sites hand to the run assembly.
    def "a supplied decoration is carried verbatim"() {
        given:
        def marker = { rounds ->
            rounds
        } as UnaryOperator<RoundEnvironmentSource>

        expect:
        new TaskGit(store, branches, worktrees, marker).midRoundPush().is(marker)
    }
}
