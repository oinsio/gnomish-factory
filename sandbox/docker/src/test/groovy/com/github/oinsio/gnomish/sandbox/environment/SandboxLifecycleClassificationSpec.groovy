package com.github.oinsio.gnomish.sandbox.environment

import spock.lang.Specification

class SandboxLifecycleClassificationSpec extends Specification {

    def "classifies a well-labelled object"() {
        given:
        def object = new ListedDockerObject('gnomish-box-k1-j', ObjectKind.CONTAINER, [
            (FactoryDockerLabels.TASK_LABEL): 'k1-j',
            (FactoryDockerLabels.MODE_LABEL): 'manual'
        ])

        when:
        def c = SandboxLifecycleClassification.of(object)

        then:
        c.environmentKey() == 'k1-j'
        c.baseTaskKey() == 'k1'
        c.role() == ObjectRole.JUDGE
        c.mode() == OwnershipMode.MANUAL
    }

    def "an object with no task label classifies to null and is skipped"() {
        expect:
        SandboxLifecycleClassification.of(new ListedDockerObject('n', ObjectKind.CONTAINER, [:])) == null
        SandboxLifecycleClassification.of(new ListedDockerObject('n', ObjectKind.CONTAINER, [(FactoryDockerLabels.TASK_LABEL): ''])) == null
    }
}
