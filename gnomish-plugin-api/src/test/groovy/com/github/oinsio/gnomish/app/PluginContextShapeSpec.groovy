package com.github.oinsio.gnomish.app

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import spock.lang.Specification

/**
 * How the host grows what it hands a plugin (FR23 of supervise-daemon-loops-and-embed-dashboard):
 * each provider factory's one {@code create} takes a context interface and nothing else, and the
 * context is made of no-argument accessors only. A new collaborator is therefore one more accessor
 * on the context — a member no factory signature names, so an existing implementor compiles against
 * the grown context unchanged, and the api gate accepts the addition as compatible (its functional
 * spec, "adding a public method passes without re-baselining").
 *
 * <p>Implements FR23 of supervise-daemon-loops-and-embed-dashboard.
 */
class PluginContextShapeSpec extends Specification {

    // Scenario: plugin/plugin-discovery / A new host collaborator extends the context
    def "FR23: #factory.simpleName's create takes only its context, an interface of no-argument accessors"() {
        given:
        Method create = factory.declaredMethods.find {
            it.name == 'create' && Modifier.isAbstract(it.modifiers)
        }

        expect: 'the factory names the context type and nothing a new collaborator could add to'
        create.parameterTypes as List == [context]

        and: 'the context is an interface, so a collaborator joins it as a member, not as a create parameter'
        context.isInterface()

        and: 'every member it already has is an accessor of the same shape a new one takes'
        def members = context.declaredMethods.findAll {
            Modifier.isAbstract(it.modifiers)
        }
        !members.isEmpty()
        members.every { it.parameterCount == 0 && it.returnType != void }

        where:
        factory | context
        TrackerAdapterFactory | TrackerAdapterContext
        CheckClientFactory | CheckClientContext
    }
}
