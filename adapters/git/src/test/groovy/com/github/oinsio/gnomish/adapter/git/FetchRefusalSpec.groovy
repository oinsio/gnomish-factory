package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR5, NFR-O2 of own-git-transfer-argv (design D4): the one parser of a transfer's validation
 * refusal. Its fixtures are the stderr real git 2.55.0 printed when a fetch and a clone under
 * {@code fetch.fsckObjects}/{@code transfer.fsckObjects} received a commit whose committer line
 * has no email — recorded once, so the grammar the four consumers rely on is git's own, not a
 * paraphrase. Every other failure a fetch can report — a fast-forward refusal, an unreachable
 * daemon, a credential prompt that could not be answered, a ref that does not exist — is not a
 * refusal and parses to empty, so it keeps reaching the classification that owns it.
 */
class FetchRefusalSpec extends Specification {

    static final String OBJECT = '4fac338eb7ab83e161dedf7bd70faca576de5c41'

    /** {@code git fetch} under {@code fetch.fsckObjects=true}, git 2.55.0. */
    static final String FETCH_REFUSAL = """\
error: object ${OBJECT}: missingEmail: invalid author/committer line - missing email
fatal: fsck error in packed object
fatal: index-pack failed
"""

    /** {@code git clone --no-local} under {@code transfer.fsckObjects=true}, git 2.55.0. */
    static final String CLONE_REFUSAL = """\
Cloning into 'badclone'...
error: object ${OBJECT}: missingEmail: invalid author/committer line - missing email
fatal: fsck error in packed object
fatal: fetch-pack: invalid index-pack output
"""

    def "FR5: a #subcommand refusal names the message id and the object git refused"() {
        when:
        def refusal = FetchRefusal.parse(UntrustedText.subprocess(stderr))

        then:
        refusal.present
        refusal.get().messageId().forLog() == 'missingEmail'
        refusal.get().object().forLog() == OBJECT

        where:
        subcommand | stderr
        'fetch' | FETCH_REFUSAL
        'clone' | CLONE_REFUSAL
    }

    def "FR5: the first refused object is the one named when git lists several"() {
        given:
        def stderr = "error: object ${OBJECT}: badTree: malformed tree\n" +
                "error: object 0123456789abcdef0123456789abcdef01234567: missingEmail: invalid author/committer line\n" +
                'fatal: fsck error in packed object\n'

        when:
        def refusal = FetchRefusal.parse(UntrustedText.subprocess(stderr)).get()

        then:
        refusal.messageId().forLog() == 'badTree'
        refusal.object().forLog() == OBJECT
    }

    def "FR5: the packed-object summary alone is a refusal that names no object"() {
        when:
        def refusal = FetchRefusal.parse(UntrustedText.subprocess('fatal: fsck error in packed object\nfatal: index-pack failed\n'))

        then:
        refusal.present
        refusal.get().messageId().forLog() == 'fsck error in packed object'
        refusal.get().object().isBlank()
    }

    // FR5, NFR-O2: the four consumers quote one factory-composed clause, so its two shapes are
    //     pinned here rather than once per consumer report.
    def "FR5: the refusal is worded once — #shape"() {
        expect:
        FetchRefusal.parse(UntrustedText.subprocess(stderr)).get().refusedObjectClause().forLog() == clause

        where:
        shape | stderr | clause
        'an object git named' | FETCH_REFUSAL | "object ${OBJECT} failed validation check missingEmail"
        'the summary alone' | 'fatal: fsck error in packed object\n' | "git reported 'fsck error in packed object' and named no object"
    }

    def "NFR-O2: both fields stay carriers of the subprocess family"() {
        when:
        def refusal = FetchRefusal.parse(UntrustedText.subprocess(FETCH_REFUSAL)).get()

        then:
        refusal.messageId().provenance() == UntrustedText.subprocess('').provenance()
        refusal.object().provenance() == UntrustedText.subprocess('').provenance()
    }

    def "FR5: #failure is not a refusal"() {
        expect:
        FetchRefusal.parse(UntrustedText.subprocess(stderr)).empty

        where:
        failure | stderr
        'non-fast-forward' | 'From ../origin\n ! [rejected]        main       -> origin/main  (non-fast-forward)\n'
        'daemon' | 'Cannot connect to the Docker daemon at unix:///var/run/docker.sock. Is the docker daemon running?\nfatal: Could not read from remote repository.\n'
        'auth' | "fatal: could not read Password for 'https://***@github.com': Device not configured\n"
        'missing ref' | "fatal: couldn't find remote ref refs/heads/bad\n"
        'object file' | 'error: object file .git/objects/4f/ac338eb7ab83e161dedf7bd70faca576de5c41 is empty\n'
        'blank' | ''
    }
}
