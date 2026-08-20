package com.github.oinsio.gnomish.architecture

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification

/**
 * The trusted-artifact exemption in {@code gradle/verification-metadata.xml} is meant to cover
 * only {@code sources}/{@code javadoc} classifier artifacts (spec: "The exemption covers only
 * sources and javadoc" of add-dependency-verification); nothing else executes, so nothing else
 * may bypass checksum pinning. A widened {@code <trust>} entry would otherwise pass the build
 * silently, with only code review standing between it and merge.
 */
class DependencyVerificationMetadataSpec extends Specification {

    private static final List<String> EXPECTED_TRUST_PATTERNS = [
        '.*-javadoc\\.jar',
        '.*-sources\\.jar'
    ]

    // implements the "exemption covers only sources and javadoc" scenario of
    // add-dependency-verification
    def "the trusted-artifact exemption matches only sources and javadoc classifiers"() {
        given: 'the trusted-artifacts block of the verification metadata'
        def metadata = Files.readString(metadataFile())
        def trustedBlock = (metadata =~ /(?s)<trusted-artifacts>(.*?)<\/trusted-artifacts>/)[0][1]
        def entries = (trustedBlock =~ /<trust\s+file="([^"]+)"\s+regex="true"\s*\/>/)
                .collect { it[1] }

        expect: 'exactly the two known classifier patterns, nothing wider'
        entries.toSet() == EXPECTED_TRUST_PATTERNS.toSet()
        entries.size() == EXPECTED_TRUST_PATTERNS.size()
    }

    private static Path metadataFile() {
        def root = Path.of(System.getProperty('repoRoot'))
        assert Files.isDirectory(root): 'repoRoot system property is not set (see bootstrap/verification.gradle)'
        def file = root.resolve('gradle/verification-metadata.xml')
        assert Files.isRegularFile(file): "verification metadata not found at ${file}"
        file
    }
}
