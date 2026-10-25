package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.sandbox.ResourceLimits
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR13 of tighten-spec-contract (design D12, finding F82): the installation's box equipment owns
 * the three projections every box built from it shares — the container settings, a role's guard
 * config directory and a task's ownership — so no consumer composes them on its own.
 */
class BoxEquipmentSpec extends Specification implements ContainerEnvironmentsFixture {

    static final ResourceLimits LIMITS = new ResourceLimits('2', '2g', 512L, '10g')

    private BoxEquipment equipment(SandboxProperties properties) {
        new BoxEquipment(docker, properties, timing, Path.of('/factory/guard-config'), OwnershipMode.MANUAL, ImageContractCheck.current())
    }

    def "FR13: a role's guard config renders under the installation's guard config root, in the key's own directory"() {
        expect:
        equipment(sandbox).guardConfig('org-repo-9-j') == Path.of('/factory/guard-config/org-repo-9-j')
    }

    def "FR13: a task's ownership is the installation's mode beside the task's project identity"() {
        expect:
        equipment(sandbox).ownership('proj-7') == new ObjectOwnership(OwnershipMode.MANUAL, 'proj-7')
    }

    def "FR13: the container settings are the sandbox config's image, runtime, limits and disk-quota opt-in"() {
        given:
        def properties = new SandboxProperties('gnomish/img', 'runsc', null, LIMITS, null, null, true, null, null, null, null)

        expect:
        equipment(properties).containerSettings() == new TaskContainerSettings('gnomish/img', 'runsc', LIMITS, true)
    }

    def "FR13: an installation without an image builds its equipment, and refuses only when a box's settings are asked for"() {
        given: 'a host-only installation: no image configured'
        def properties = new SandboxProperties(null, null, null, null, null, null, false, null, null, null, null)
        def equipment = equipment(properties)

        when:
        equipment.containerSettings()

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('factory.sandbox.image')
    }
}
