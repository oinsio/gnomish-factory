package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult
import com.github.oinsio.gnomish.app.port.tracker.HumanReply
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask

/**
 * A strict read-only {@link Tracker} fake: {@code listReady}/{@code listOpen} are recorded and
 * answered from fixed data; every write/coordination method throws, so a read-only code path
 * ({@link BoardCommand}, {@link DashboardCommand}) that ever calls one fails the test immediately
 * rather than silently passing (NG3 of add-board-command).
 */
class RecordingReadOnlyTracker implements Tracker {

    private final List<ReadyTask> ready
    private final List<OpenTask> open
    int listReadyCalls = 0
    int listOpenCalls = 0
    Integer lastLimit

    RecordingReadOnlyTracker(List<ReadyTask> ready, List<OpenTask> open) {
        this.ready = ready
        this.open = open
    }

    @Override
    List<ReadyTask> listReady(int limit) {
        listReadyCalls++
        lastLimit = limit
        ready
    }

    @Override
    List<OpenTask> listOpen() {
        listOpenCalls++
        open
    }

    private static UnsupportedOperationException notReadOnly(String method) {
        new UnsupportedOperationException("BoardCommand must never call Tracker.$method (NG3 of add-board-command)")
    }

    @Override
    TrackerTask fetchTask(TaskRef ref) {
        throw notReadOnly('fetchTask')
    }

    @Override
    List<HumanReply> collectDecisions(TaskRef ref) {
        throw notReadOnly('collectDecisions')
    }

    @Override
    ClaimResult claim(TaskRef ref, String instanceId) {
        throw notReadOnly('claim')
    }

    @Override
    void release(TaskRef ref) {
        throw notReadOnly('release')
    }

    @Override
    void park(TaskRef ref, ParkReason reason, String report) {
        throw notReadOnly('park')
    }

    @Override
    void finish(TaskRef ref, String summary) {
        throw notReadOnly('finish')
    }

    @Override
    void declineFinished(TaskRef ref, String message) {
        throw notReadOnly('declineFinished')
    }

    @Override
    void recordAbort(TaskRef ref, AbortRecord record) {
        throw notReadOnly('recordAbort')
    }

    @Override
    void recordProgress(TaskRef ref) {
        throw notReadOnly('recordProgress')
    }

    @Override
    void acknowledgeDecision(TaskRef ref, String decisionText) {
        throw notReadOnly('acknowledgeDecision')
    }

    @Override
    void postNote(TaskRef ref, String text) {
        throw notReadOnly('postNote')
    }

    @Override
    HeartbeatResult heartbeat(TaskRef ref, String progressPayload) {
        throw notReadOnly('heartbeat')
    }

    @Override
    RemoveStaleClaimResult removeStaleClaim(TaskRef ref, ClaimVersion observedVersion) {
        throw notReadOnly('removeStaleClaim')
    }
}
