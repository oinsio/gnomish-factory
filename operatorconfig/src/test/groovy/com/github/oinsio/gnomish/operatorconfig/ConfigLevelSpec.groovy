package com.github.oinsio.gnomish.operatorconfig

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import spock.lang.Specification

/**
 * {@link ConfigLevel}: the declaration the key → level table is derived from. The derivation reads
 * the annotation back by reflection from each record component, so the two properties it rests on
 * are pinned here — a class-retained annotation would read back as absent, and one propagated to
 * the accessor instead of the component would be missed by a component scan. The annotated record
 * is {@link LevelledRecord}, declared in Java.
 *
 * <p>FR6 of add-project-registry.
 */
class ConfigLevelSpec extends Specification {

    def "FR6: the level is read back from the record component at run time"() {
        when:
        def component = LevelledRecord.recordComponents.find {
            it.name == 'image'
        }

        then:
        component.getAnnotation(ConfigLevel).value() == Level.SANDBOX_BOUNDARY
    }

    def "FR6: the annotation is runtime-retained and targets record components only"() {
        expect:
        ConfigLevel.getAnnotation(Retention).value() == RetentionPolicy.RUNTIME
        ConfigLevel.getAnnotation(Target).value() as List == [ElementType.RECORD_COMPONENT]
    }

    def "FR6: the four levels of design D4, and no other"() {
        expect:
        Level.values() as List == [
            Level.HOST,
            Level.PROJECT,
            Level.ANY,
            Level.SANDBOX_BOUNDARY
        ]
    }
}
