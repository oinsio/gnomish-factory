package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import spock.lang.Specification

/**
 * {@link PipelineMapper} hands the verify-check mapping the law's credentialed executors (NFR-S1 of
 * migrate-verify-to-executor-contract, design D6): a check naming an executor the law grants
 * credentials runs in a fresh box unless it declares otherwise, while the mapping errors keep their
 * order — the stages', then the tracker's, then the declarations'.
 */
class PipelineMapperCredentialedCheckSpec extends Specification implements PipelineMapperFixtureSupport {

    private static ConfigDto law(Map<String, ExecutorDeclarationDto> executors) {
        new ConfigDto('1', new AutonomyDto(1), null, null, executors)
    }

    private static ExecutorDeclarationDto program(List<String> credentials, Map<String, Object> settings = null) {
        new ExecutorDeclarationDto('program', ['./run'], settings, credentials, null)
    }

    private static VerifyCheckDto.Executor check(String executor, String verifyIn = null, String interval = '10s') {
        new VerifyCheckDto.Executor(executor, 'gate', null, interval, '2m', null, null, verifyIn, null, null)
    }

    // NFR-S1: the default follows the grant of the executor each check names
    def "a check's absent verify-in is fresh-box for a credentialed executor and same-box otherwise"() {
        when:
        def result = PipelineMapper.map(law([
            sonarqube: program(['SONAR_TOKEN']),
            acme: program([])
        ]), [
            entry('p', ioLessStage([
                check('sonarqube'),
                check('acme'),
                check('sonarqube', 'same-box')
            ]))
        ])

        then:
        result.errors().isEmpty()
        result.definition().stages()[0].verify().collect {
            (it as VerifyCheck.Executor).verifyIn()
        } == [
            VerifyCheck.VerifyIn.FRESH_BOX,
            VerifyCheck.VerifyIn.SAME_BOX,
            VerifyCheck.VerifyIn.SAME_BOX
        ]
    }

    // NFR-R1 of load-pipeline-config: mapping the declarations first leaves the error order as it was
    def "a declaration's mapping error still follows the stages' mapping errors"() {
        when:
        def result = PipelineMapper.map(law([sonarqube: program([], [key: null])]), [
            entry('p', ioLessStage([
                check('sonarqube', null, 'soon')
            ]))
        ])

        then:
        result.definition() == null
        result.errors()*.where() == [
            'verify[0].interval',
            'executors.sonarqube.settings.key'
        ]
    }
}
