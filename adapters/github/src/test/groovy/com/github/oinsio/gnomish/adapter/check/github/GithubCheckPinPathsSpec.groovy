package com.github.oinsio.gnomish.adapter.check.github

import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.time.Duration
import spock.lang.Specification

/**
 * GithubCheckPinPaths (FR4 of add-external-check-github-actions, design D3): the adapter
 * contributes exactly the checkId workflow file to the pin set, narrow rather than
 * directory-wide — the union with law-declared paths is the future pin-check guard's job
 * (add-sandbox-core FR16/D10), not this adapter's.
 *
 * Implements FR4 of add-external-check-github-actions.
 */
class GithubCheckPinPathsSpec extends Specification {

    def "contributes exactly the checkId workflow file, one path and nothing else"() {
        given:
        def check = new VerifyCheck.External('.github/workflows/ci.yml', 'github', Duration.ofSeconds(30), Duration.ofMinutes(10), VerifyCheck.TimeoutClass.QUALITY)

        when:
        def pinPaths = GithubCheckPinPaths.contributedBy(check)

        then:
        pinPaths == ['.github/workflows/ci.yml'] as Set
    }

    def "a different checkId contributes its own single path, proving the pin follows checkId rather than being hardcoded"() {
        given:
        def check = new VerifyCheck.External('.github/workflows/lint.yml', 'github', Duration.ofSeconds(30), Duration.ofMinutes(10), VerifyCheck.TimeoutClass.QUALITY)

        when:
        def pinPaths = GithubCheckPinPaths.contributedBy(check)

        then:
        pinPaths == ['.github/workflows/lint.yml'] as Set
    }
}
