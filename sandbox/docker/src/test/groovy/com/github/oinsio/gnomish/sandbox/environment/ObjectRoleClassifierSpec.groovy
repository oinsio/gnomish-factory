package com.github.oinsio.gnomish.sandbox.environment

import spock.lang.Specification

/**
 * `execution-environment` delta, "Container realization of lifecycle roles": role and base task
 * key recovered from the object's own name and environment-key label, never from a live-task
 * snapshot.
 */
class ObjectRoleClassifierSpec extends Specification {

    def "classifies containers by name prefix and the -j/-v suffix on the label key"() {
        expect:
        ObjectRoleClassifier.classify(ObjectKind.CONTAINER, 'gnomish-box-k1', 'k1') == ObjectRole.MAIN_BOX
        ObjectRoleClassifier.classify(ObjectKind.CONTAINER, 'gnomish-box-k1-j', 'k1-j') == ObjectRole.JUDGE
        ObjectRoleClassifier.classify(ObjectKind.CONTAINER, 'gnomish-box-k1-v', 'k1-v') == ObjectRole.VERIFICATION
        ObjectRoleClassifier.classify(ObjectKind.CONTAINER, 'gnomish-guard-k1', 'k1') == ObjectRole.GUARD
    }

    def "a container matching no factory name pattern is the seed helper"() {
        expect:
        ObjectRoleClassifier.classify(ObjectKind.CONTAINER, 'a1b2c3d4e5f6', 'k1') == ObjectRole.SEED_HELPER
    }

    def "classifies volumes and networks by name prefix and the same suffix rule"() {
        expect:
        ObjectRoleClassifier.classify(ObjectKind.VOLUME, 'gnomish-vol-k1', 'k1') == ObjectRole.MAIN_BOX
        ObjectRoleClassifier.classify(ObjectKind.VOLUME, 'gnomish-vol-k1-j', 'k1-j') == ObjectRole.JUDGE
        ObjectRoleClassifier.classify(ObjectKind.NETWORK, 'gnomish-net-k1', 'k1') == ObjectRole.MAIN_BOX
        ObjectRoleClassifier.classify(ObjectKind.NETWORK, 'gnomish-net-k1-v', 'k1-v') == ObjectRole.VERIFICATION
    }

    def "a volume or network matching no factory name pattern is unrecognized"() {
        expect:
        ObjectRoleClassifier.classify(ObjectKind.VOLUME, 'something-else', 'k1') == ObjectRole.UNRECOGNIZED
        ObjectRoleClassifier.classify(ObjectKind.NETWORK, 'something-else', 'k1') == ObjectRole.UNRECOGNIZED
    }

    def "disposableOnSight is true only for guard, judge, verification, and seed helper"() {
        expect:
        ObjectRole.GUARD.disposableOnSight()
        ObjectRole.JUDGE.disposableOnSight()
        ObjectRole.VERIFICATION.disposableOnSight()
        ObjectRole.SEED_HELPER.disposableOnSight()
        !ObjectRole.MAIN_BOX.disposableOnSight()
        !ObjectRole.UNRECOGNIZED.disposableOnSight()
    }

    def "baseTaskKey strips a -j or -v suffix, and is identity otherwise"() {
        expect:
        ObjectRoleClassifier.baseTaskKey('k1-j') == 'k1'
        ObjectRoleClassifier.baseTaskKey('k1-v') == 'k1'
        ObjectRoleClassifier.baseTaskKey('k1') == 'k1'
    }
}
