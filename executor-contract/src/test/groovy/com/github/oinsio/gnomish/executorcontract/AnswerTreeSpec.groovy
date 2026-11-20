package com.github.oinsio.gnomish.executorcontract

import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * {@link ExecutorContractJson#answerTree}, the answer slot an in-process built-in fills (FR1 of
 * migrate-verify-to-executor-contract): the tree is the document the contract's answer writer gives,
 * so {@link Answer#read} reads it back as the answer it was made from.
 */
class AnswerTreeSpec extends Specification {

    // FR1: a built-in's SARIF answer is the document a program would have written; D23: a run's URL included
    def "FR1: a verdict answer's tree reads back as the same verdict"() {
        given:
        def result = new SarifResult(null, SarifLevel.ERROR, UntrustedText.manifest('missing file: a.txt'),
                UntrustedText.manifest('a.txt'), null)
        def verdict = new Answer.Verdict([
            new SarifRun(UntrustedText.factory('files-exist'), [result], null),
            new SarifRun(UntrustedText.factory('ci'), [], UntrustedText.factory('https://ci/runs/1'))
        ])

        when:
        def tree = ExecutorContractJson.answerTree(verdict)
        def reading = Answer.read(tree, { String text ->
            UntrustedText.factory(text)
        })

        then:
        tree.get('kind').textValue() == 'verdict'
        tree.get('sarif').get('version').textValue() == Answer.Verdict.SARIF_VERSION
        reading instanceof AnswerReading.Parsed
        def read = (reading as AnswerReading.Parsed).answer() as Answer.Verdict
        read.runs()*.tool()*.raw() == ['files-exist', 'ci']
        read.runs()*.runUrl()*.raw() == [null, 'https://ci/runs/1']
        read.runs()[0].results()*.level() == [SarifLevel.ERROR]
        read.runs()[0].results()*.message()*.raw() == ['missing file: a.txt']
        read.runs()[0].results()*.uri()*.raw() == ['a.txt']
    }
}
