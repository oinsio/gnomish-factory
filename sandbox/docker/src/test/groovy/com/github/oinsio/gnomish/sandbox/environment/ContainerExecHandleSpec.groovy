package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.domain.engine.port.Clock
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ResourceLimits
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification

/**
 * FR3 of add-sandbox-core: {@code ContainerTaskExecutionEnvironment#exec}
 * returns the handle over the process it started — the seam every round and
 * check waits on — never a null after the side effects.
 *
 * <p>New spec file for task 6.1 of split-into-modules: the returned handle was
 * consumed only by specs now in other modules, so its null-return mutant
 * survived this module's own suite; per-module PIT (D6) needs the kill here —
 * same reasoning as {@code ExecCommandSpec} at task 3.1.
 */
class ContainerExecHandleSpec extends Specification {

    static final ResourceLimits LIMITS = new ResourceLimits('2', '2g', 512L, '10g')

    static final Instant STARTED = Instant.parse('2026-08-14T10:00:00Z')

    def clock = { -> STARTED } as Clock
    def harvester = { String container, String branch -> } as ContainerHarvest

    // FR3: the caller waits on exactly the process exec started
    def "exec returns the live handle over the started process"() {
        given: 'a materialized container environment over a scripted docker seam'
        def docker = new ScriptedDockerCli(new ScriptedProcess())
        def e = new ContainerTaskExecutionEnvironment(
                docker, 'k1', Path.of('/factory/clone'), harvester, 'gnomish/img', 'runc', LIMITS, false, clock,
                ChildEnvAllowlist.none(), new ObjectOwnership(OwnershipMode.TRACKED, 'proj-1'))
        e.materialize('task/x', null)

        when:
        def handle = e.exec(new ExecCommand(['true'], [:], null, false))

        then: 'the handle is wired to the scripted process and this exec\'s start instant'
        handle.waitForExit() == 0
        handle.startedAt() == STARTED
    }
}
