package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.BasePin
import com.github.oinsio.gnomish.app.port.git.BaseRefKind
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome
import com.github.oinsio.gnomish.app.port.git.TaskRecord
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The {@code task.json} a resume scenario starts from, on a FIXED clock. Every resume spec — take,
 * git-mode and container-mode — routes on the branch's own recorded outcome, so they all need the
 * same record built with a different outcome/escalation pair; carrying one factory here keeps the
 * three specs disagreeing only about what they assert.
 *
 * <p>Split out of {@link RunChainFakes} (which implements it, so no spec has to name both).
 */
trait TaskRecordFakes {

    static final Instant NOW = Instant.parse('2026-08-14T12:00:00Z')
    static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC)

    /** The {@code task.json} of a branch with no recorded outcome yet. */
    TaskRecord freshRecord(String taskId = 'PROJ-1') {
        recordWith(null, null, false, taskId)
    }

    /**
     * The {@code task.json} of an ANSWERED branch: a human decision is durable on it and no
     * outcome is recorded, the shape whose acknowledge may still be owed (FR12 of
     * harden-task-branch-contract).
     */
    TaskRecord recordWithDecision(String body, String taskId = 'PROJ-1') {
        new TaskRecord(new TaskContext(taskId, UntrustedText.tracker('title'), UntrustedText.tracker('body'), [
            new Decision(body, 'build', 'tracker', NOW)
        ]),
        'base-sha', NOW, null, null, false, BasePin.UNPINNED)
    }

    /**
     * The {@code task.json} of a branch carrying a durable base pin as an AUTONOMOUS claim writes
     * it — ref, the namespace origin classified it into, and the tier that produced the name. That
     * is the shape every task created by {@code take}/{@code serve} has, and the one a resume
     * rebinds its law from (FR7, FR12, D7 revised 2026-09-10).
     */
    TaskRecord recordPinnedTo(String baseRef, String baseCommit = 'base-sha', String taskId = 'PROJ-1') {
        new TaskRecord(new TaskContext(taskId, UntrustedText.tracker('title'), UntrustedText.tracker('body'), List.<Decision> of()),
                baseCommit, NOW, null, null, false,
                new BasePin(baseRef, BaseRefKind.BRANCH, BaseRule.CONFIGURED_DEFAULT))
    }

    /**
     * The {@code task.json} of a branch pinned by the MANUAL tier, which classifies nothing: ref
     * and rule without a kind (D7, revised 2026-09-10). The same shape every document written
     * before the kind existed has, so this is also the legacy-pin fixture.
     */
    TaskRecord recordManuallyPinnedTo(String baseRef, String baseCommit = 'base-sha', String taskId = 'PROJ-1') {
        new TaskRecord(new TaskContext(taskId, UntrustedText.tracker('title'), UntrustedText.tracker('body'), List.<Decision> of()),
                baseCommit, NOW, null, null, false,
                new BasePin(baseRef, null, BaseRule.EXPLICIT_ARGUMENT))
    }

    /**
     * The {@code task.json} of a branch whose last visit RECORDED how it ended, optionally with the
     * pending-terminal-write marker a park sets before the tracker write confirms.
     */
    TaskRecord recordWith(RecordedOutcome outcome, EscalationReport escalation = null,
            boolean pendingTerminalWrite = false, String taskId = 'PROJ-1') {
        new TaskRecord(new TaskContext(taskId, UntrustedText.tracker('title'), UntrustedText.tracker('body'), List.<Decision> of()),
                'base-sha', NOW, outcome, escalation, pendingTerminalWrite, BasePin.UNPINNED)
    }
}
