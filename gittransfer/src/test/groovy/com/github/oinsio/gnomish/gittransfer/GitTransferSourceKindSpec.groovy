package com.github.oinsio.gnomish.gittransfer

import spock.lang.Specification

/**
 * FR1, FR2, M4 of own-git-transfer-argv (design D2): what a source contributes to a transfer is
 * its transport (the URL, or {@code origin}), its protocol allowlist and its isolation shape —
 * nothing else. The common set, the fetch flags and the stripped overrides are the owner's and
 * come out the same for every source, so a backend that reaches its box another way (a VM or a
 * remote runner behind its own {@code ext::} command) gets them without naming a flag.
 */
class GitTransferSourceKindSpec extends Specification {

    private static final Refspec REFSPEC = new Refspec('refs/heads/gnomish/T-1:refs/heads/gnomish/T-1')

    private static final String BOX_BRANCH = 'gnomish/T-1'

    private static final QuarantineRef QUARANTINE = QuarantineRef.forTask('T-1')

    /** The owner's part of a fetch: everything in the argv up to the source's transport word. */
    private static List<String> ownersPart(GitTransfer transfer, String transport) {
        transfer.argv().subList(0, transfer.argv().indexOf(transport)) - '--refmap='
    }

    // FR1, M4 of own-git-transfer-argv: one owner, two inputs; a new transport changes neither half
    //     of the common set nor the validation it carries.
    // Scenario: git/git-transfer / A new source kind needs no new flags
    def "FR1: a #backend source supplies its transport and allowlist, and the owner's flags come out unchanged"() {
        given: 'the reference: the docker box every existing harvest reaches'
        def docker = GitTransfer.harvest(new TransferSource.Container('ext::docker exec -i box %S'), BOX_BRANCH, QUARANTINE)

        when:
        def transfer = source instanceof TransferSource.Origin
                ? GitTransfer.fetch(source, REFSPEC)
                : GitTransfer.harvest(source, BOX_BRANCH, QUARANTINE)

        then: 'the common set, the fetch flags and the validation are the reference\'s, word for word'
        ownersPart(transfer, transport) == ownersPart(docker, 'ext::docker exec -i box %S')
        transfer.argv().contains('fetch.fsckObjects=true')

        and: 'what differs is what the source supplies: its transport word, its allowlist and its isolation'
        transfer.argv().last() == (source instanceof TransferSource.Origin ? REFSPEC : QUARANTINE.harvestRefspec(BOX_BRANCH)).value()
        transfer.environment().get('GIT_ALLOW_PROTOCOL') == Optional.of(source.protocolAllowlist())
        source.configurationIsolation().every {
            transfer.environment().get(it.key) == it.value
        }

        and: 'the inherited overrides are stripped for every source alike'
        docker.environment().findAll {
            !it.value.present
        }.every {
            transfer.environment().get(it.key) == Optional.empty()
        }

        where:
        backend | source | transport
        'VM' | new TransferSource.Container('ext::ssh vm-7 git-upload-pack %S') | 'ext::ssh vm-7 git-upload-pack %S'
        'remote runner' | new TransferSource.Container('ext::runner-cli exec job-3 %S') | 'ext::runner-cli exec job-3 %S'
        'origin' | TransferSource.ORIGIN | 'origin'
    }
}
