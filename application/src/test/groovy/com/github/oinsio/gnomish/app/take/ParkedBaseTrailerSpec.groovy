package com.github.oinsio.gnomish.app.take

import com.github.oinsio.gnomish.baseref.UnderdeterminedCause
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.gitobjects.ObjectId
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR2, FR6, FR9, FR12, FR13 of add-base-ref-resolution: every deterministic base park closes with
 * the same paragraph — no attempt spent, no claim released, re-claiming pointless — followed by the
 * one remedy that report's own failure has. The four reports are driven here rather than each in
 * its own spec: the point of {@link ParkedBaseTrailer} is that the sentence an operator learns to
 * recognize is identical across them, which only a spec seeing all four can assert.
 */
class ParkedBaseTrailerSpec extends Specification {

    private static final String SHARED = 'No stage attempt was spent and the claim was not released:' +
    ' the failure is deterministic, so re-claiming would only repeat it. '

    def "every base park report closes with the shared trailer and its own remedy (#report)"() {
        expect:
        rendered.endsWith(SHARED + remedy)

        where:
        report | rendered | remedy
        'underdetermined' | FreshClaimBaseReport.underdetermined(
                'PROJ-1', UnderdeterminedCause.DESIGNATOR_CONFLICT, [UntrustedText.tracker('main')],
                'two values on one task') |
                "Fix the task's base designator or the project's allowed bases, then return the task to work."
        'refresh refused' | FreshClaimBaseReport.refused(
                'PROJ-1', 'release/1.18', UntrustedText.subprocess('origin holds no such ref')) |
                ParkedBaseTrailer.REPOINT_BASE
        'resume unresolved' | ResumeBaseReport.unresolved(
                'PROJ-1', 'release/1.18', UntrustedText.subprocess('origin holds no such ref')) |
                ParkedBaseTrailer.REPOINT_BASE
        'law failed to load' | BaseLawReport.of('PROJ-1', 'release/1.18',
                ObjectId.of('0123456789abcdef0123456789abcdef01234567'),
                [
                    new ConfigError('pipeline.yaml', 'stages', "missing required field 'stages'")
                ]) |
                'Fix .gnomish/ on the base and return the task to work.'
    }
}
