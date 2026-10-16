package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.gittransfer.GitTransfer
import com.github.oinsio.gnomish.gittransfer.TransferSource
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR4, NFR-S1 of own-git-transfer-argv: a harvest's protocol allowlist names {@code ext} alone,
 * so the harvest argv cannot reach the network whatever URL it carries. The owner's container
 * value is run by real git with only its URL swapped for a network one; git refuses the transport
 * by name before any connection.
 */
class HarvestNetworkRefusalSpec extends Specification implements BareGitRepoFixture {

    private static final String EXT_URL = 'ext::docker exec -i box %S'

    @TempDir
    Path tempDir

    // FR4 of own-git-transfer-argv: the allowlist is the whole protocol policy of the harvest.
    // Scenario: git/git-transfer / A harvest cannot reach the network
    def "FR4: the harvest argv with a #protocol URL in place of its ext:: URL is refused by git"() {
        given: "the owner's harvest value, whose allowlist names ext alone"
        def harvest = GitTransfer.harvest(new TransferSource.Container(EXT_URL), 'gnomish/T-1', FactoryRegisterRefs.quarantine('T-1'))
        assert harvest.environment().get('GIT_ALLOW_PROTOCOL') == Optional.of('ext')
        Path clone = initWorkingRepo(tempDir, 'factory-clone')

        when: 'the same argv and environment run with the URL replaced'
        def argv = harvest.argv().collect { it == EXT_URL ? url : it }
        def builder = new ProcessBuilder(['git'] + argv).directory(clone.toFile())
        def environment = TestChildEnvironment.cleared(builder)
        harvest.environment().each { String name, Optional<String> value ->
            if (value.present) {
                environment[name] = value.get()
            } else {
                environment.remove(name)
            }
        }
        def process = builder.start()
        def stderr = process.errorStream.text
        assert process.waitFor(30, TimeUnit.SECONDS)

        then: 'git names the transport it refused'
        process.exitValue() != 0
        stderr.contains("transport '${protocol}' not allowed")

        where:
        protocol | url
        'https' | 'https://127.0.0.1:9/repo.git'
        'ssh' | 'ssh://git@127.0.0.1:9/repo.git'
    }
}
