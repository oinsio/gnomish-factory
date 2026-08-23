package com.github.oinsio.gnomish.adapter.git

import spock.lang.Specification

/**
 * NFR-S2 of fix-lifecycle-push: git output that carries a remote URL's userinfo — the token or
 * user:password pair an operator may have embedded in {@code origin} — is scrubbed before the
 * factory logs it or puts it in a report a tracker will publish.
 *
 * The git messages in the table below are real, captured from git 2.55 against a local HTTP
 * server and against github.com (see CredentialScrub's own javadoc for which of them git already
 * anonymizes on its own and which one it does not).
 */
class CredentialScrubSpec extends Specification {

    def "NFR-S2: #description"() {
        expect:
        CredentialScrub.scrub(raw) == scrubbed

        where:
        description | raw | scrubbed

        'the credential-prompt message that echoes a PAT used as the username loses the token' |
                "fatal: could not read Password for 'https://ghp_FAKETOKEN1234567890@github.com': Device not configured" |
                "fatal: could not read Password for 'https://***@github.com': Device not configured"

        'a user:password pair in a push URL loses both halves' |
                "fatal: unable to access 'https://alice:s3cretPAT@github.com/acme/widgets.git/': not found" |
                "fatal: unable to access 'https://***@github.com/acme/widgets.git/': not found"

        'every occurrence on a multi-line stderr is scrubbed, not only the first' |
                "error: failed to push some refs to 'http://alice:pw@host/r.git'\nhint: see 'http://alice:pw@host/r.git'" |
                "error: failed to push some refs to 'http://***@host/r.git'\nhint: see 'http://***@host/r.git'"

        'a URL without userinfo is left byte-for-byte alone' |
                "fatal: unable to access 'https://github.com/acme/widgets.git/': Could not resolve host" |
                "fatal: unable to access 'https://github.com/acme/widgets.git/': Could not resolve host"

        'an @ after the authority ends is not mistaken for userinfo' |
                "fatal: path 'https://github.com/acme/a@b' does not exist" |
                "fatal: path 'https://github.com/acme/a@b' does not exist"

        'an scp-style remote keeps its account name — it carries no secret' |
                "fatal: could not read from remote repository git@github.com:acme/widgets.git" |
                "fatal: could not read from remote repository git@github.com:acme/widgets.git"

        'the ext:: transport URL the container harvest fetch uses is untouched' |
                "fatal: transport 'ext::docker exec -i box git %S /work' died" |
                "fatal: transport 'ext::docker exec -i box git %S /work' died"

        'empty output stays empty' | '' | ''
    }

    // The mask must not itself look like a usable credential, and must be visible enough that an
    // operator reading a WARN sees that something was removed rather than assuming the URL had no
    // credentials at all.
    def "NFR-S2: the mask is a fixed marker, not a truncation of the secret"() {
        expect:
        CredentialScrub.MASK == '***'
        !CredentialScrub.scrub("https://ghp_SECRETVALUE@host/r.git").contains('SECRET')
    }
}
