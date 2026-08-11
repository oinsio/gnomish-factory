package com.github.oinsio.gnomish.adapter.environment

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR7, NFR-S2 of add-sandbox-core (design D4): the guard config renderer — the
 * constant mitmproxy addon (default-deny allowlist, TLS forwarded unmodified,
 * marked JSON denial lines) and the operator allowlist rendered as JSON, with a
 * conservative entry grammar so a config typo cannot smuggle syntax into the
 * rendered file.
 */
class EgressGuardConfigSpec extends Specification {

    @TempDir
    Path tempDir

    def "FR7: render writes the addon script and the allowlist into the config dir"() {
        when:
        EgressGuardConfig.render(tempDir.resolve('cfg'), ['registry.example.com'])

        then: 'both files exist under the (created) config dir'
        def script = tempDir.resolve('cfg').resolve('guard.py')
        def allowlist = tempDir.resolve('cfg').resolve('allowlist.json')
        Files.exists(script)
        Files.exists(allowlist)

        and: 'the script is the non-intercepting default-deny addon'
        def source = Files.readString(script)
        source.contains('ignore_connection = True')
        source.contains('GNOMISH-EGRESS-DENY ')
        source.contains('def http_connect')
        source.contains('def request')

        and: 'the allowlist file carries the rendered entries'
        Files.readString(allowlist) == '["registry.example.com"]'
    }

    def "FR7: render is idempotent and overwrites stale content"() {
        given: 'a previous render with a different allowlist'
        def dir = tempDir.resolve('cfg')
        EgressGuardConfig.render(dir, ['old.example.com'])

        when:
        EgressGuardConfig.render(dir, ['new.example.com'])

        then:
        Files.readString(dir.resolve('allowlist.json')) == '["new.example.com"]'
    }

    def "FR7: allowlist entries render lower-cased, in operator order"() {
        expect:
        EgressGuardConfig.allowlistJson([
            'Registry.Example.COM',
            '*.maven.org',
            '10.0.0.7'
        ]) ==
        '["registry.example.com","*.maven.org","10.0.0.7"]'
    }

    def "FR7: an empty allowlist renders as the empty array — default-deny with nothing allowed"() {
        expect:
        EgressGuardConfig.allowlistJson([]) == '[]'
    }

    def "FR7: an entry outside the host grammar is rejected naming the entry"() {
        when:
        EgressGuardConfig.allowlistJson([entry])

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('factory.sandbox.egress-allowlist')

        where:
        entry << [
            '',
            '   ',
            'host with space',
            'host"quote',
            'host,comma',
            'a/b',
            'evil*.example.com'
        ]
    }

    def "FR7: rendered config is readable by the guard container's non-root user whatever the umask"() {
        given: 'a config dir and stale files locked down to owner-only (a restrictive umask would do this)'
        def dir = tempDir.resolve('cfg')
        Files.createDirectories(dir)
        Files.writeString(dir.resolve('guard.py'), 'stale')
        Files.writeString(dir.resolve('allowlist.json'), 'stale')
        [
            dir,
            dir.resolve('guard.py'),
            dir.resolve('allowlist.json')
        ].each {
            Files.setPosixFilePermissions(it, PosixFilePermissions.fromString(it == dir ? 'rwx------' : 'rw-------'))
        }

        when:
        EgressGuardConfig.render(dir, ['registry.example.com'])

        then: 'the dir is traversable and both files readable by others'
        Files.getPosixFilePermissions(dir).containsAll([
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE
        ])
        Files.getPosixFilePermissions(dir.resolve('guard.py')).contains(PosixFilePermission.OTHERS_READ)
        Files.getPosixFilePermissions(dir.resolve('allowlist.json')).contains(PosixFilePermission.OTHERS_READ)
    }

    def "NFR-S2: the deny marker constant matches the parser's expectation"() {
        expect: 'renderer and parser agree on the marker, so denials are never silently unparsed'
        EgressGuardConfig.DENY_MARKER == 'GNOMISH-EGRESS-DENY '
    }
}
