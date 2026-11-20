package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.executorcontract.Answer
import com.github.oinsio.gnomish.executorcontract.AnswerReading
import com.github.oinsio.gnomish.executorcontract.Bounds
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.SarifResult
import com.github.oinsio.gnomish.executorcontract.Workspace
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * The requests the {@code files-exist} executor specs send and the reading of the answers they get
 * back: a {@code verdict} request carrying the settings as the law loader maps them, and the SARIF
 * results of a completed result read through the contract's own answer reading.
 */
final class FilesExistRequests {

    private FilesExistRequests() {}

    static ExecutorRequest verdict(Map<String, ?> settings) {
        new ExecutorRequest(Role.VERDICT, new Workspace('.'), new Policy.ReadOnly(), RoleInputs.verdict('files exist'),
                ExecutorContractJson.settingsTree(settings), Bounds.none(), [], null)
    }

    static ExecutorRequest files(List<?> files) {
        verdict([files: files])
    }

    /** The results of every run of a completed result's SARIF answer, in the order written. */
    static List<SarifResult> results(ExecutorResult result) {
        def reading = Answer.read(result.answer(), { String text ->
            UntrustedText.factory(text)
        })
        assert reading instanceof AnswerReading.Parsed
        ((reading as AnswerReading.Parsed).answer() as Answer.Verdict).runs().collectMany {
            it.results()
        }
    }
}
