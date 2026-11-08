package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * Design D10 of define-executor-contract, row {@code ExecutorOutcomeClassifier}: one class maps an
 * executor result's status to attempt accounting and escalation. A second switch over the status,
 * or a second constructor of the escalation the classifier decides, compiles, passes its own spec
 * and splits the failure-class table in two.
 *
 * <p>Four constructions and one read are gated, each by an allowlist of files with a reason:
 * <ul>
 *   <li>{@code EscalationReport.CannotExecute} and {@code EscalationReport.BudgetExhausted}, and the
 *       {@code Stop.BudgetExhausted} a limit records: the classifier, plus the readers that rebuild
 *       the value from what was already decided (the wire, a recorded stop) and decide nothing;
 *   <li>a pattern read of an {@code ExecutionResult} status variant ({@code case}, {@code instanceof},
 *       a cast, an import of the nested type): the classifier only;
 *   <li>{@code Verdict.CannotVerify}: its owner {@code VerdictDerivation} (D9 of
 *       migrate-verify-to-executor-contract) and the exemptions D10 names, each tagged with the
 *       pickup that removes it, so the list cannot grow without a named follow-up;
 *   <li>{@code ExecutorFailure}: nowhere — task 7.4 deleted it, and the rule keeps a revival out.
 * </ul>
 * Every allowlisted file is asserted reached and still matching, so a pickup that removes a
 * construction fails here until its row is deleted. The whole production tree is scanned, comments
 * stripped, except {@code :test-fixtures}: its doubles script a verdict or an escalation as their
 * answer and decide nothing the engine reads.
 *
 * <p>G4, M2, FR7 of define-executor-contract.
 */
class OutcomeClassifierOwnerSpec extends Specification {

    private static final String ENGINE = 'domain/src/main/java/com/github/oinsio/gnomish/domain/engine/'
    private static final String STATE = 'adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/state/'
    private static final String AGENT = 'adapters/agent/src/main/java/com/github/oinsio/gnomish/adapter/agent/'
    private static final String CLASSIFIER = ENGINE + 'ExecutorOutcomeClassifier.java'

    /**
     * The one owner of a check's verdict (design D5, D9 of migrate-verify-to-executor-contract): it
     * builds the {@code CannotVerify} of a malformed answer and of a failed/infrastructure status.
     */
    private static final String DERIVATION = ENGINE + 'VerdictDerivation.java'

    /** The owners of their rows; every other exemption names its pickup. */
    private static final List<String> OWNERS = [CLASSIFIER, DERIVATION]

    /** Test doubles script outcomes on purpose; they classify nothing the engine reads. */
    private static final String FIXTURES = "test-fixtures/"

    /** The pickups an exemption may name; a reason that names none of them is a finding. */
    static final List<String> PICKUPS = [
        'dto: ',
        'record: ',
        'extract-agent-round-runner: ',
    ]

    private static final String JUDGE = 'extract-agent-round-runner: the judge builds its own verdict (D9, NG1)'

    static final List<Map> RULES = [
        [name: 'escalation the classifier decides',
            pattern: /\bnew\s+EscalationReport\s*\.\s*(CannotExecute|BudgetExhausted)\s*\(/,
            imported: /import\s+com\.github\.oinsio\.gnomish\.domain\.engine\.EscalationReport\.(CannotExecute|BudgetExhausted|\*)\s*;/,
            bare: /\bnew\s+(CannotExecute|BudgetExhausted)\s*\(/,
            allowed: [
                (CLASSIFIER) : 'the owner',
                (ENGINE + 'StopEscalation.java') : 'record: the escalation a recorded Stop.BudgetExhausted raises, live and on pre-flight',
                (STATE + 'TaskEscalationMapper.java'): 'dto: task.json escalation rebuilt from the wire',
            ]],
        [name: 'stop the classifier decides',
            pattern: /\bnew\s+Stop\s*\.\s*BudgetExhausted\s*\(/,
            imported: /import\s+com\.github\.oinsio\.gnomish\.domain\.engine\.Stop\.(BudgetExhausted|\*)\s*;/,
            bare: /\bnew\s+BudgetExhausted\s*\(/,
            allowed: [
                (CLASSIFIER) : 'the owner',
                (STATE + 'StateStopMapper.java'): 'dto: state.json stop rebuilt from the wire',
            ]],
        [name: 'status read',
            pattern: /\b(case|instanceof)\s+ExecutionResult\s*\.\s*(Completed|Failed|Pending)\b|\(\s*ExecutionResult\s*\.\s*(Completed|Failed|Pending)\s*\)|import\s+com\.github\.oinsio\.gnomish\.domain\.engine\.ExecutionResult\.(Completed|Failed|Pending|\*)\s*;/,
            allowed: [
                (CLASSIFIER): 'the owner',
                // Task 4.5 of define-executor-contract (NFR-O2, UX3): the round's log and ledger lines
                //     spell the status in the contract's wire tokens; a rendering, it classifies nothing.
                'application/src/main/java/com/github/oinsio/gnomish/status/RoundOutcomeWords.java':
                'dto: the round status spelled in the contract wire tokens, deciding nothing',
            ]],
        [name: 'check-side verdict',
            pattern: /\bnew\s+Verdict\s*\.\s*CannotVerify\s*\(/,
            imported: /import\s+com\.github\.oinsio\.gnomish\.domain\.engine\.Verdict\.(CannotVerify|\*)\s*;/,
            bare: /\bnew\s+CannotVerify\s*\(/,
            // Task 2.2 of migrate-verify-to-executor-contract routed ShellCommandCheckRunner,
            //     FilesExistCheckRunner, ExternalPolling and VerifyOrchestrator through the owner.
            allowed: [
                (DERIVATION) : 'the owner',
                (AGENT + 'JudgeVerdictExtractor.java') : JUDGE,
                (AGENT + 'JudgeCriteriaPreflight.java') : JUDGE,
                (AGENT + 'JudgeCannotVerify.java') : JUDGE,
                (STATE + 'StateJsonMapper.java') : 'dto: a recorded check verdict rebuilt from state.json',
            ]],
        [name: 'thrown executor failure',
            pattern: /\bnew\s+([\w.]+\.)?ExecutorFailure\s*\(/,
            // Empty since task 7.4 of define-executor-contract: the agent-cli round reports the
            // contract status instead of throwing, and the type is deleted; the rule keeps it gone.
            allowed: [:]],
    ]

    // G4, M2: each construction and read lives only in its allowlisted files, each reached and still matching
    def "G4, M2: #rule.name lives only in its allowlisted files"() {
        given:
        def tree = productionCode()

        expect: 'the scan really reached the tree'
        tree.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        and: 'every allowlisted file is reached and still matches, so a removed construction drops its row'
        rule.allowed.keySet().findAll {
            !tree.containsKey(it) || !matches(rule, tree[it])
        }.toList() == []

        and: 'no other file matches'
        offenders(rule, tree) == []

        where:
        rule << RULES
    }

    // G4: an exemption names the pickup that removes it, so the list cannot grow silently
    def "G4: every exemption other than the owner names its pickup"() {
        expect:
        RULES.collectMany { rule ->
            rule.allowed.findAll { path, reason ->
                !OWNERS.contains(path) && !PICKUPS.any { reason.startsWith(it) }
            }.keySet() as List
        } == []
    }

    // G4: a planted second classifier is reported by the same verdict the tree is judged by
    def "G4: a planted source that switches on the status and builds CannotExecute is reported by both rules"() {
        given:
        def planted = '''
            final class PlantedClassifier {
                EscalationReport of(ExecutionResult result) {
                    return switch (result.status()) {
                        case ExecutionResult.Failed f -> new EscalationReport.CannotExecute(f.reason(), List.of());
                        default -> null;
                    };
                }
            }
        '''
        def path = ENGINE + 'PlantedClassifier.java'
        def tree = productionCode() + [(path): RepoSourceTree.stripComments(planted)]

        expect:
        RULES.findAll {
            offenders(it, tree) == [path]
        }*.name == [
            'escalation the classifier decides',
            'status read'
        ]
    }

    // The detector: every spelling a construction or a read can take is found, a mention is not
    def "the detector: #shape"() {
        expect:
        RULES.findAll {
            matches(it, RepoSourceTree.stripComments(source))
        }*.name == names

        where:
        shape | source || names
        'qualified CannotExecute' | 'var r = new EscalationReport.CannotExecute(c, d);' || [
            'escalation the classifier decides'
        ]
        'imported BudgetExhausted' | 'import com.github.oinsio.gnomish.domain.engine.EscalationReport.BudgetExhausted;\nvar r = new BudgetExhausted(b);' || [
            'escalation the classifier decides'
        ]
        'a stop of a limit' | 'var s = new Stop.BudgetExhausted(b);' || ['stop the classifier decides']
        'a case on a status' | 'case ExecutionResult.Pending p -> x;' || ['status read']
        'an instanceof on a status' | 'if (s instanceof ExecutionResult.Completed) {}' || ['status read']
        'a cast to a status' | 'var f = (ExecutionResult.Failed) s;' || ['status read']
        'a status import' | 'import com.github.oinsio.gnomish.domain.engine.ExecutionResult.Failed;' || ['status read']
        'a verdict built' | 'return new Verdict.CannotVerify(r, d);' || ['check-side verdict']
        'a failure thrown' | 'throw new ExecutorFailure(e, d, t);' || ['thrown executor failure']
        'a failed input built' | 'var s = new ExecutionResult.Failed(INFRASTRUCTURE, r, List.of());' || []
        'a wire dto built' | 'var d = new EscalationReportDto.CannotExecute("cannotExecute", c, List.of());' || []
        'a bare DTO name without the domain import' | 'var d = new CannotExecute("cannotExecute", c, List.of());' || []
        'another verdict type' | 'return new RangeVerdict.CannotVerify(e);' || []
        'a mention in a comment' | '// new EscalationReport.CannotExecute(c, d)\nvar x = 1;' || []
    }

    /** Every production source by its relative path, comments stripped. */
    private static Map<String, String> productionCode() {
        RepoSourceTree.productionSources {
            !it.startsWith(FIXTURES)
        }.collectEntries {
            [(RepoSourceTree.relative(it)): RepoSourceTree.code(it)]
        }
    }

    private static List<String> offenders(Map rule, Map<String, String> codeByPath) {
        codeByPath.findAll { path, code ->
            matches(rule, code) && !rule.allowed.containsKey(path)
        }.keySet().sort()
    }

    private static boolean matches(Map rule, String code) {
        code =~ rule.pattern || (rule.imported && code =~ rule.imported && code =~ rule.bare)
    }
}
