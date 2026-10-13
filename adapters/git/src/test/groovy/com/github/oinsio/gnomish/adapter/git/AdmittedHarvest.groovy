package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.RoundToken
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import java.nio.file.Path

/**
 * The admission of what a harvest landed, for specs outside this package that drive a real box
 * (design D18 of tighten-spec-contract): a harvest fills the task's quarantine and nothing else, so
 * a spec that wants the box's work on the task branch admits it, as the mid-round listener does.
 */
final class AdmittedHarvest {

    private AdmittedHarvest() {}

    /** Admits the quarantined harvest of {@code taskId} under the mid-round rule; answers the verdict. */
    static AdmittedRange admit(Path clone, String taskId) {
        def admission = new HarvestAdmission(new GitProcessRunner(), clone)
        def tip = new GitProcessRunner().run(clone, 'rev-parse', 'refs/heads/' + com.github.oinsio.gnomish.app.git.TaskIdSanitizer.branchName(taskId))
                .stdout().forParsing().trim()
        admission.admit(taskId, admission.quarantined(taskId), AdmissionRule.midRound(new AttemptKey(taskId, 'stage', 0), RoundToken.of(tip)))
    }

    /** Whether {@code verdict} admitted the range. */
    static boolean admitted(Object verdict) {
        verdict instanceof AdmittedRange.Admitted
    }

    /** Whether {@code verdict} refused the range as a boundary violation. */
    static boolean refused(Object verdict) {
        verdict instanceof AdmittedRange.Refused
    }
}
