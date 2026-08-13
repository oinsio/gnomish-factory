package com.github.oinsio.gnomish.adapter.secrets

import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Function
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The env/file {@code SecretsProvider} adapter (design D12): resolution order
 * (direct env var, {@code <name>_FILE} indirection), the fail-closed contract
 * (absent/blank/unreadable resolve to empty, never a silent value), and the
 * file-over-env precedence.
 *
 * <p>Implements FR18, NFR-S1 of add-sandbox-core.
 */
class EnvFileSecretsProviderSpec extends Specification {

    @TempDir
    Path tempDir

    private static Function<String, String> envOf(Map<String, String> vars) {
        { String name -> vars.get(name) } as Function
    }

    // FR18: a direct env var resolves by name
    def "resolves a secret from a direct environment variable"() {
        given: 'GNOMISH_GITHUB_TOKEN present in the environment'
        def provider = new EnvFileSecretsProvider(envOf([GNOMISH_GITHUB_TOKEN: 'ghp_secret']))

        expect: 'find returns its value'
        provider.find('GNOMISH_GITHUB_TOKEN') == Optional.of('ghp_secret')
    }

    // NFR-S1: an absent secret is empty, never a silent value
    def "an absent secret resolves to empty"() {
        given: 'nothing set for the name'
        def provider = new EnvFileSecretsProvider(envOf([:]))

        expect: 'find is empty'
        provider.find('GNOMISH_GITHUB_TOKEN') == Optional.empty()
    }

    // NFR-S1: a blank direct value is treated as absent (fail-closed)
    def "a blank direct value resolves to empty"() {
        given: 'the name set to whitespace only'
        def provider = new EnvFileSecretsProvider(envOf([GNOMISH_GITHUB_TOKEN: '   ']))

        expect: 'find is empty'
        provider.find('GNOMISH_GITHUB_TOKEN') == Optional.empty()
    }

    // FR18: the <name>_FILE indirection reads the value from a file, stripped
    def "resolves a secret from the <name>_FILE indirection, stripping surrounding whitespace"() {
        given: 'a secret file with a trailing newline and the _FILE var pointing at it'
        def secretFile = tempDir.resolve('token')
        Files.writeString(secretFile, 'ghp_from_file\n')
        def provider = new EnvFileSecretsProvider(envOf([GNOMISH_GITHUB_TOKEN_FILE: secretFile.toString()]))

        expect: 'find returns the stripped file contents'
        provider.find('GNOMISH_GITHUB_TOKEN') == Optional.of('ghp_from_file')
    }

    // FR18: the file indirection wins over a direct value when both are set
    def "the _FILE indirection takes precedence over a direct value"() {
        given: 'both the direct var and the _FILE var set'
        def secretFile = tempDir.resolve('token')
        Files.writeString(secretFile, 'from_file')
        def provider = new EnvFileSecretsProvider(
                envOf([GNOMISH_GITHUB_TOKEN: 'from_env', GNOMISH_GITHUB_TOKEN_FILE: secretFile.toString()]))

        expect: 'the file value is used'
        provider.find('GNOMISH_GITHUB_TOKEN') == Optional.of('from_file')
    }

    // NFR-S1: a referenced-but-unreadable file is empty, never a fall-through to the direct value
    def "a referenced but missing file resolves to empty, never the direct value"() {
        given: 'the _FILE var points at a nonexistent path, with a direct value also set'
        def provider = new EnvFileSecretsProvider(
                envOf([GNOMISH_GITHUB_TOKEN: 'from_env',
                    GNOMISH_GITHUB_TOKEN_FILE: tempDir.resolve('absent').toString()]))

        expect: 'find is empty — the misconfigured path fails loudly, not silently'
        provider.find('GNOMISH_GITHUB_TOKEN') == Optional.empty()
    }

    // NFR-S1: a blank _FILE value is ignored, falling back to the direct value
    def "a blank _FILE value is ignored and the direct value is used"() {
        given: 'the _FILE var blank and a direct value present'
        def provider = new EnvFileSecretsProvider(
                envOf([GNOMISH_GITHUB_TOKEN: 'from_env', GNOMISH_GITHUB_TOKEN_FILE: '  ']))

        expect: 'the direct value resolves'
        provider.find('GNOMISH_GITHUB_TOKEN') == Optional.of('from_env')
    }

    // NFR-S1: a file whose contents are blank resolves to empty
    def "a file with blank contents resolves to empty"() {
        given: 'a secret file holding only whitespace'
        def secretFile = tempDir.resolve('blank')
        Files.writeString(secretFile, '\n  \n')
        def provider = new EnvFileSecretsProvider(envOf([GNOMISH_GITHUB_TOKEN_FILE: secretFile.toString()]))

        expect: 'find is empty'
        provider.find('GNOMISH_GITHUB_TOKEN') == Optional.empty()
    }
}
