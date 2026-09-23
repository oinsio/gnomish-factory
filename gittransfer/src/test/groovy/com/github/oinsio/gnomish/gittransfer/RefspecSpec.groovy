package com.github.oinsio.gnomish.gittransfer

import spock.lang.Specification

/**
 * FR1, FR3 of own-git-transfer-argv: the one refspec a transfer names is a value the owner can
 * place after the options terminator with nothing to check at the call site — a leading {@code -}
 * and an empty string are refused where the value is made, not where git would misread them.
 */
class RefspecSpec extends Specification {

    def "a refspec is carried verbatim"() {
        expect:
        new Refspec(value).value() == value

        where:
        value << [
            '+refs/heads/main:refs/remotes/origin/main',
            'refs/tags/v1:refs/tags/v1',
            '0123456789abcdef0123456789abcdef01234567',
            'refs/heads/gnomish/T-1:refs/heads/gnomish/T-1',
        ]
    }

    // FR3: git fetch parses options after the remote name too, so a refspec that looks like an
    //     option is refused here rather than honoured as one.
    def "a refspec that looks like an option or names nothing is refused"() {
        when:
        new Refspec(value)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(reason)

        where:
        value || reason
        '' || 'must not be blank'
        '   ' || 'must not be blank'
        '-' || "must not start with '-'"
        '--dry-run' || "must not start with '-'"
        '-x:refs/x' || "must not start with '-'"
    }

    def "a missing refspec is refused outright"() {
        when:
        new Refspec(null)

        then:
        thrown(NullPointerException)
    }

    def "equal refspecs are one value"() {
        expect:
        new Refspec('refs/heads/a:refs/heads/a') == new Refspec('refs/heads/a:refs/heads/a')
        new Refspec('refs/heads/a:refs/heads/a') != new Refspec('refs/heads/b:refs/heads/b')
    }
}
