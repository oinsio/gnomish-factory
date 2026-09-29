package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * FR8, M2 of fix-operator-blockers (design D5, single-owner row "rejectUnknownOptions"): a raw
 * option read — {@code containsOption} or {@code getOptionNames} on {@code ApplicationArguments}
 * — happens only in the argument owner and the parsers it serves.
 *
 * <p>A component that decides from raw options on its own keeps a second copy of some parser's
 * accepted set, and a command line that copy does not know slips past the unknown-option check:
 * {@code ManualRunRunner}'s {@code RUN_FLAGS} gate did exactly that. The scan covers both modules
 * that share the {@code app} package, {@code application} and {@code bootstrap}, because the task
 * 5.4 sweep over {@code application} alone missed the {@code bootstrap} copy; and it asserts it
 * reached every allowlisted file, so a moved or renamed parser fails here rather than leaving the
 * allowlist naming nothing. {@code :bootstrap} owns it for the reason {@link
 * ClaimlessGitBoundarySpec} gives.
 */
class RawOptionReadBoundarySpec extends Specification {

    /** The two modules whose production sources hold the {@code app} package. */
    private static final List<String> SCANNED_MODULES = ['application/', 'bootstrap/']

    private static final String APP = 'application/src/main/java/com/github/oinsio/gnomish/app/'

    /** The parsers, the argument owner and the two helpers the run parser delegates to. */
    private static final Set<String> ALLOWED = [
        'RunArgumentsParser',
        'StatusArgumentsParser',
        'UsageArgumentsParser',
        'TakeArgumentsParser',
        'ServeArgumentsParser',
        'BoardArgumentsParser',
        'DashboardArgumentsParser',
        'ArgumentsParsingSupport',
        'GitFlagsValidator',
        'InteractiveModeParser'
    ].collect { APP + it + '.java' } as Set

    /**
     * A call or a method reference: the {@code RUN_FLAGS} gate read options through
     * {@code args::containsOption}, which a plain {@code .containsOption(} search does not see.
     */
    private static final Pattern RAW_READ = ~/(\.|::)\s*(containsOption|getOptionNames)\b/

    // FR8, M2: comments are stripped — a javadoc may name the calls; only compiled code reads options.
    def "FR8: only the argument parsers read raw options in application and bootstrap"() {
        given: 'every production source of the two modules, as the compiler sees it'
        Map<String, String> code = RepoSourceTree.productionSources { path ->
            SCANNED_MODULES.any { path.startsWith(it) }
        }.collectEntries {
            [(RepoSourceTree.relative(it)): RepoSourceTree.code(it)]
        } as Map<String, String>

        expect: 'the scan reached both modules and every allowlisted file'
        SCANNED_MODULES.every { module ->
            code.keySet().any {
                it.startsWith(module)
            }
        }
        code.keySet().containsAll(ALLOWED)

        and: 'no other production source reads a raw option'
        code.findAll { String path, String text ->
            !(path in ALLOWED) && text =~ RAW_READ
        }.keySet().isEmpty()
    }
}
