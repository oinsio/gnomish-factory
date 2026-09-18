package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.adapter.agent.MissingResultEventException
import com.github.oinsio.gnomish.adapter.git.BranchStateFileMissingException
import com.github.oinsio.gnomish.adapter.git.FactoryCloneHardeningException
import com.github.oinsio.gnomish.adapter.git.GitPersistFailedException
import com.github.oinsio.gnomish.adapter.git.GitResyncFailedException
import com.github.oinsio.gnomish.adapter.git.HarvestFailedException
import com.github.oinsio.gnomish.adapter.git.HarvestRefusedException
import com.github.oinsio.gnomish.adapter.git.WorktreeCreationFailedException
import com.github.oinsio.gnomish.adapter.law.UnreadableLawFileException
import com.github.oinsio.gnomish.app.port.git.GitSalvageFailedException
import com.github.oinsio.gnomish.sandbox.environment.DockerCommandFailedException
import com.github.oinsio.gnomish.sandbox.environment.DockerUnavailableException
import com.github.oinsio.gnomish.sandbox.environment.GuardUnavailableException
import com.github.oinsio.gnomish.sandbox.environment.SelfCheckFailedException
import com.github.oinsio.gnomish.subprocess.Termination
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR5, design D5 of type-untrusted-text: the exceptions that carry a machine's own words carry
 * them as {@link UntrustedText}, and compose their message through the carrier's log exit.
 *
 * <p>Why one spec across the families rather than an assertion in each exception's own spec: what
 * is pinned is not one message's wording but a property of the whole set — the message of an
 * exception built from a carrier is inert, whatever medium the carrier came from. An exception
 * that later takes a {@code String} for the same detail fails to compile against this table rather
 * than passing quietly, which is what makes the escape hatch closed rather than merely unused
 * ({@code implementation.md}, item 3). Lives in {@code :bootstrap} for the reason
 * {@link UntrustedTextGateSpec} does: this is the module whose test classpath sees every layer.
 *
 * <p>The corpus entry is the classic forgery attempt: a line break that would open a second log
 * record, an ANSI sequence that would drive the operator's terminal, and a plausible-looking
 * forged record behind them.
 */
class TypedExceptionMessageSpec extends Specification {

    /** What an attacker would put in a captured stream to forge a record or drive a terminal. */
    private static final String HOSTILE = "boom\n[2J2026-01-01 00:00:00 ERROR forged record\r"

    def "FR5: #exception renders its carrier through the log exit, never raw"() {
        given: 'the detail as it was captured'
        def carried = mint.call(HOSTILE) as UntrustedText

        when: 'the exception is built from it'
        def message = build.call(carried).message

        then: 'the message carries the exit\'s rendering of it, byte for byte'
        message.contains(carried.forLog())

        and: 'so nothing in it can open a second record or drive a terminal'
        !message.contains('\n')
        !message.contains('\r')
        !message.contains('')

        where:
        [exception, mint, build] << carriers()
    }

    /** Every exception design D5 gives a carrier, with the family its detail comes from. */
    private static List<List> carriers() {
        [
            [
                'BranchStateFileMissingException',
                { UntrustedText.subprocess(it) },
                {
                    new BranchStateFileMissingException('refs/heads/x', 'state.json', it)
                }
            ],
            [
                'FactoryCloneHardeningException',
                { UntrustedText.subprocess(it) },
                { new FactoryCloneHardeningException('/clones/x', it) }
            ],
            [
                'GitPersistFailedException',
                { UntrustedText.subprocess(it) },
                {
                    new GitPersistFailedException('T-1', 'verify', 0, 'commit', it)
                }
            ],
            [
                'GitResyncFailedException',
                { UntrustedText.subprocess(it) },
                {
                    new GitResyncFailedException('resync', 'git reset', Termination.EXITED, 1, it)
                }
            ],
            [
                'GitSalvageFailedException',
                { UntrustedText.container(it) },
                {
                    new GitSalvageFailedException('T-1', 'in-box salvage commit', it)
                }
            ],
            [
                'HarvestFailedException',
                { UntrustedText.subprocess(it) },
                { new HarvestFailedException('gnomish/task-x', it) }
            ],
            [
                'HarvestFailedException (partial output)',
                { UntrustedText.container(it) },
                { new HarvestFailedException('gnomish/task-x', 'fetch', it) }
            ],
            [
                'HarvestRefusedException',
                { UntrustedText.subprocess(it) },
                { new HarvestRefusedException('gnomish/task-x', it) }
            ],
            [
                'WorktreeCreationFailedException',
                { UntrustedText.subprocess(it) },
                {
                    new WorktreeCreationFailedException('T-1', 'gnomish/task-x', it)
                }
            ],
            [
                'DockerCommandFailedException',
                { UntrustedText.subprocess(it) },
                {
                    new DockerCommandFailedException('create network', 'k1', 'gnomish-box-k1', it)
                }
            ],
            [
                'DockerUnavailableException',
                { UntrustedText.subprocess(it) },
                {
                    new DockerUnavailableException('docker daemon is unreachable', it)
                }
            ],
            [
                'GuardUnavailableException',
                { UntrustedText.subprocess(it) },
                {
                    new GuardUnavailableException('egress guard for k1 could not be started', it)
                }
            ],
            [
                'SelfCheckFailedException',
                { UntrustedText.container(it) },
                {
                    new SelfCheckFailedException('non-root', 'could not read the in-box uid, output', it)
                }
            ],
            [
                'MissingResultEventException',
                { UntrustedText.agent(it) },
                { new MissingResultEventException(it) }
            ],
            [
                'UnreadableLawFileException',
                { UntrustedText.manifest(it) },
                {
                    new UnreadableLawFileException('stages/verify/criteria.md', it)
                }
            ]
        ]
    }
}
