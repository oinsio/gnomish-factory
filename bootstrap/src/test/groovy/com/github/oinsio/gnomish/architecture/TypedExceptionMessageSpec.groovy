package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.adapter.agent.MissingResultEventException
import com.github.oinsio.gnomish.adapter.git.BranchStateFileMissingException
import com.github.oinsio.gnomish.adapter.git.FactoryCloneHardeningException
import com.github.oinsio.gnomish.adapter.git.GitPersistFailedException
import com.github.oinsio.gnomish.adapter.git.GitResyncFailedException
import com.github.oinsio.gnomish.adapter.git.HarvestFailedException
import com.github.oinsio.gnomish.adapter.git.HarvestRefusedException
import com.github.oinsio.gnomish.adapter.git.RoundBoundaryViolationException
import com.github.oinsio.gnomish.adapter.git.WorktreeCreationFailedException
import com.github.oinsio.gnomish.adapter.law.UnreadableLawFileException
import com.github.oinsio.gnomish.app.port.git.BranchLocationRefusedException
import com.github.oinsio.gnomish.app.port.git.BranchLocationUnavailableException
import com.github.oinsio.gnomish.app.port.git.GitSalvageFailedException
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException
import com.github.oinsio.gnomish.app.port.git.GitVersionRefusedException
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent
import com.github.oinsio.gnomish.app.port.git.TaskListingFailedException
import com.github.oinsio.gnomish.sandbox.environment.DockerCommandFailedException
import com.github.oinsio.gnomish.sandbox.environment.DockerUnavailableException
import com.github.oinsio.gnomish.sandbox.environment.GuardUnavailableException
import com.github.oinsio.gnomish.sandbox.environment.SelfCheckFailedException
import com.github.oinsio.gnomish.subprocess.Termination
import com.github.oinsio.gnomish.testsupport.CarrierConstructors
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import spock.lang.Shared
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

    /** The escape character, written as a name rather than as a byte no diff viewer shows. */
    private static final String ESC = Character.toString(27)

    /** What an attacker would put in a captured stream to forge a record or drive a terminal. */
    private static final String HOSTILE = "boom\n${ESC}[2J2026-01-01 00:00:00 ERROR forged record\r"

    @Shared
    JavaClasses productionClasses = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .importPackages('com.github.oinsio.gnomish')

    // D5, implementation.md item 1 ("the consumer list, checked off"): a hand-kept table of
    //     exceptions is exactly the shape that goes quietly stale — the next throwable to take a
    //     carrier joins the design's set without joining this spec, and the property above is then
    //     asserted of everything but the newcomer. GitTaskRepositoryException was that newcomer.
    //     So the table is pinned against the bytecode the sink gate already derives its own
    //     exemption from ({@link com.github.oinsio.gnomish.testsupport.CarrierConstructors}): the
    //     two sets are the same set, read two ways.
    def "FR5: the table names every production throwable that declares a carrier detail"() {
        given: 'every production throwable with an UntrustedText constructor parameter'
        def declared = CarrierConstructors.throwableTypeNamesIn(productionClasses)

        expect: 'the derivation really found the family — an empty answer would pass any table'
        declared.size() >= 15

        and: 'and the table below covers each one, and names nothing that is not one'
        declared.toSorted() == tabledExceptions()
    }

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
        !message.contains(ESC)

        where:
        [exception, mint, build] << carriers()
    }

    /**
     * The distinct exception types the table exercises. A row's label may carry a parenthetical
     * naming which constructor arm it drives ({@code HarvestFailedException (partial output)}), so
     * the type name is the label up to it.
     */
    private static List<String> tabledExceptions() {
        carriers().collect {
            (it[0] as String).replaceFirst(/ \(.*\)$/, '')
        }.toUnique().toSorted()
    }

    /** Every exception design D5 gives a carrier, with the family its detail comes from. */
    private static List<List> carriers() {
        [
            [
                // The reason is a sentence the factory composed around git's stderr, quoted
                // through the log exit — `FACTORY`, not `SUBPROCESS` (design D3, task 7.6).
                'BranchLocationUnavailableException',
                { String raw -> UntrustedText.factory(raw) },
                { UntrustedText carried ->
                    new BranchLocationUnavailableException('GF-1', carried)
                }
            ],
            [
                // The report is factory prose quoting git's validation words through the log exit
                // (FR5 of own-git-transfer-argv).
                'BranchLocationRefusedException',
                { String raw -> UntrustedText.factory(raw) },
                { UntrustedText carried ->
                    new BranchLocationRefusedException('GF-1', carried)
                }
            ],
            [
                'BranchStateFileMissingException',
                { String raw -> UntrustedText.subprocess(raw) },
                { UntrustedText carried ->
                    new BranchStateFileMissingException('refs/heads/x', 'state.json', carried)
                }
            ],
            [
                'FactoryCloneHardeningException',
                { String raw -> UntrustedText.subprocess(raw) },
                { UntrustedText carried ->
                    new FactoryCloneHardeningException('/clones/x', carried)
                }
            ],
            [
                'GitPersistFailedException',
                { String raw -> UntrustedText.subprocess(raw) },
                { UntrustedText carried ->
                    new GitPersistFailedException('T-1', 'verify', 0, 'commit', carried)
                }
            ],
            [
                'GitResyncFailedException',
                { String raw -> UntrustedText.subprocess(raw) },
                { UntrustedText carried ->
                    new GitResyncFailedException('resync', 'git reset', Termination.EXITED, 1, carried)
                }
            ],
            [
                'GitTaskRepositoryException',
                { String raw -> UntrustedText.subprocess(raw) },
                { UntrustedText carried ->
                    new GitTaskRepositoryException('T-1', TaskLifecycleEvent.STARTED, 'git commit', carried)
                }
            ],
            [
                'GitSalvageFailedException',
                { String raw -> UntrustedText.container(raw) },
                { UntrustedText carried ->
                    new GitSalvageFailedException('T-1', 'in-box salvage commit', carried)
                }
            ],
            [
                'HarvestFailedException',
                { String raw -> UntrustedText.subprocess(raw) },
                { UntrustedText carried ->
                    new HarvestFailedException('gnomish/task-x', carried)
                }
            ],
            [
                'HarvestFailedException (partial output)',
                { String raw -> UntrustedText.container(raw) },
                { UntrustedText carried ->
                    new HarvestFailedException('gnomish/task-x', 'fetch', carried)
                }
            ],
            [
                'HarvestRefusedException',
                { String raw -> UntrustedText.subprocess(raw) },
                { UntrustedText carried ->
                    new HarvestRefusedException('gnomish/task-x', carried)
                }
            ],
            [
                'WorktreeCreationFailedException',
                { String raw -> UntrustedText.subprocess(raw) },
                { UntrustedText carried ->
                    new WorktreeCreationFailedException('T-1', 'gnomish/task-x', carried)
                }
            ],
            [
                'DockerCommandFailedException',
                { String raw -> UntrustedText.subprocess(raw) },
                { UntrustedText carried ->
                    new DockerCommandFailedException('create network', 'k1', 'gnomish-box-k1', carried)
                }
            ],
            [
                // The re-stating arm delegates to the arm above for its message and only attaches
                // the lower failure as a cause, so the carrier's rendering must survive that
                // delegation — the day it composes a message of its own, this row is what asks.
                'DockerCommandFailedException (restated cause)',
                { String raw -> UntrustedText.subprocess(raw) },
                { UntrustedText carried ->
                    new DockerCommandFailedException(
                    'create network', 'k1', 'gnomish-box-k1', carried, new IllegalStateException('lower'))
                }
            ],
            [
                'DockerUnavailableException',
                { String raw -> UntrustedText.subprocess(raw) },
                { UntrustedText carried ->
                    new DockerUnavailableException('docker daemon is unreachable', carried)
                }
            ],
            [
                'GuardUnavailableException',
                { String raw -> UntrustedText.subprocess(raw) },
                { UntrustedText carried ->
                    new GuardUnavailableException('egress guard for k1 could not be started', carried)
                }
            ],
            [
                'SelfCheckFailedException',
                { String raw -> UntrustedText.container(raw) },
                { UntrustedText carried ->
                    new SelfCheckFailedException('non-root', 'could not read the in-box uid, output', carried)
                }
            ],
            [
                'MissingResultEventException',
                { String raw -> UntrustedText.agent(raw) },
                { UntrustedText carried ->
                    new MissingResultEventException(carried)
                }
            ],
            [
                // The read-volume arm is the one production takes when the drain kept byte
                // accounting; it appends factory prose after the carrier, so the carrier's
                // rendering must still survive byte for byte rather than being re-flattened.
                'MissingResultEventException (read volume)',
                { String raw -> UntrustedText.agent(raw) },
                { UntrustedText carried ->
                    new MissingResultEventException(carried, 65_528L, 3)
                }
            ],
            [
                // The harvested-boundary arm quotes the paths git printed; the other arms compose
                // factory prose, so the parameter is the carrier either way (task 7.10).
                'RoundBoundaryViolationException',
                { String raw -> UntrustedText.subprocess(raw) },
                { UntrustedText carried ->
                    new RoundBoundaryViolationException('T-1', carried)
                }
            ],
            [
                // What git --version printed instead of a version line, quoted through the log
                // exit (FR10, NFR-R2 of own-git-transfer-argv).
                'GitVersionRefusedException',
                { String raw -> UntrustedText.subprocess(raw) },
                { UntrustedText carried ->
                    new GitVersionRefusedException('2.45.1', carried, 'why')
                }
            ],
            [
                'TaskListingFailedException',
                { String raw -> UntrustedText.subprocess(raw) },
                { UntrustedText carried ->
                    new TaskListingFailedException('refs/heads/gnomish/*', 128, carried)
                }
            ],
            [
                'UnreadableLawFileException',
                { String raw -> UntrustedText.manifest(raw) },
                { UntrustedText carried ->
                    new UnreadableLawFileException('stages/verify/criteria.md', carried)
                }
            ]
        ]
    }
}
