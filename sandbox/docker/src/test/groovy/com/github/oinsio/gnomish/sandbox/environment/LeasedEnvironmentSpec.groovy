package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.domain.engine.Denial
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.DenialCursor
import com.github.oinsio.gnomish.sandbox.DenialRead
import com.github.oinsio.gnomish.sandbox.DenialRestoration
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.nio.charset.StandardCharsets
import spock.lang.Specification

/**
 * FR12 of add-sandbox-core: the leased view forwards every environment
 * operation to the environment currently held by its supplier — values come
 * back from the leased box, never fabricated — while lifecycle operations stay
 * with the lease and are refused.
 *
 * <p>FR6 of fix-denial-attribution-durability: the port's three denial default
 * methods are forwarded too. Answering them here with the interface's empty
 * constants is what left the predecessor's cursor feature inert in production.
 */
class LeasedEnvironmentSpec extends Specification {

    def box = Mock(TaskExecutionEnvironment)
    def leased = new LeasedEnvironment({ box })

    // FR12: a channel read comes back from the leased environment, never an empty fabrication
    def "readFile returns the leased environment's bytes"() {
        given:
        def bytes = 'findings'.getBytes(StandardCharsets.UTF_8)

        when:
        def read = leased.readFile('f.json', 1024)

        then:
        1 * box.readFile('f.json', 1024) >> Optional.of(bytes)
        read.get() == bytes
    }

    // FR12: scratch root and passport are the leased environment's own values
    def "scratchRoot and passport come from the leased environment"() {
        given:
        def passport = CapabilityPassport.container()

        when:
        def scratch = leased.scratchRoot()
        def seenPassport = leased.passport()

        then:
        1 * box.scratchRoot() >> '/gnomish/scratch'
        1 * box.passport() >> passport
        scratch == '/gnomish/scratch'
        seenPassport.is(passport)
    }

    // FR12: exec, putFile and harvest act on the environment of the stage in flight
    def "exec, putFile and harvest forward to the leased environment"() {
        given:
        def command = ExecCommand.of(['sh', '-c', 'true'])
        def handle = Mock(ExecHandle)

        when:
        def seenHandle = leased.exec(command)
        leased.putFile('a.txt', 'x'.getBytes(StandardCharsets.UTF_8))
        leased.harvest()

        then:
        1 * box.exec(command) >> handle
        1 * box.putFile('a.txt', _)
        1 * box.harvest()
        seenHandle.is(handle)
    }

    // FR6: readDenials is the leased box's own delta AND its position, not the port's empty default
    def "readDenials comes from the leased environment, findings and position together"() {
        given:
        def denial = new Finding('egress denied: POST paste.example/x', 'paste.example', null)
        def cursor = new DenialCursor('guard-abc', '2026-08-28T10:00:00.000000001Z')

        when:
        def read = leased.readDenials()

        then:
        1 * box.readDenials() >> new DenialRead([Denial.unidentified(denial)], Optional.of(cursor))
        read.denials()*.finding() == [denial]
        read.positionAfter().get().is(cursor)
    }

    // FR6: the cursor the factory commits with an attempt is the leased box's, not an empty default
    def "denialCursor comes from the leased environment"() {
        given:
        def cursor = new DenialCursor('guard-abc', '2026-08-28T10:00:00.000000001Z')

        when:
        def read = leased.denialCursor()

        then:
        1 * box.denialCursor() >> Optional.of(cursor)
        read.get().is(cursor)
    }

    // FR6: a restore offer reaches the leased box — swallowed here, no resume would ever position
    def "restoreDenials reaches the leased environment"() {
        given:
        def cursor = new DenialCursor('guard-abc', '2026-08-28T10:00:00.000000001Z')

        when:
        leased.restoreDenials(DenialRestoration.at(cursor))
        then:
        1 * box.restoreDenials(DenialRestoration.at(cursor))
    }

    // FR12: the lease owns the lifecycle — the view refuses materialize and dispose
    def "lifecycle operations are refused"() {
        when:
        leased.invokeMethod(operation, args as Object[])

        then:
        thrown(UnsupportedOperationException)
        0 * box._

        where:
        operation | args
        'materialize' | ['gnomish/t', null]
        'dispose' | []
    }
}
