package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import spock.lang.Specification

/**
 * Design D10 of define-executor-contract, row {@code UsageFold}: one pure fold sums the factory's
 * recorded usage, and every writer of {@code totals} and every reader that displays a sum takes it
 * from there (design D15). A second sum — a {@code plus} on a usage value, a field-wise addition of
 * one token kind across two values, a {@code merge} with a summing function, a cost addition, a
 * running total of tokens — compiles, passes its own spec and is the collapsed jq sum this change
 * removes, in another file.
 *
 * <p>Every rule allows {@code UsageFold} and the named exemptions, each with its reason; every
 * allowlisted file is asserted reached and still matching, so a pickup that removes a sum fails
 * here until its row is deleted. The whole production tree is scanned, comments stripped, and every
 * script under {@code .gnomish/stages} for a {@code jq} {@code reduce} over tokens. Rendering one
 * value's own kinds side by side (a cache column of one round's figure) is display, not a sum of
 * usage, and is not gated.
 *
 * <p>FR17, NFR-O3, G5, M5 of define-executor-contract.
 */
class UsageFoldOwnerBoundarySpec extends Specification {

    private static final String OWNER = 'domain/src/main/java/com/github/oinsio/gnomish/domain/engine/UsageFold.java'
    private static final String DASHBOARD = 'application/src/main/java/com/github/oinsio/gnomish/dashboard/'
    private static final String AGGREGATOR = DASHBOARD + 'LedgerAggregator.java'

    /** The reasons an exemption may give; one that gives none of them is a finding. */
    static final List<String> REASONS = ['dashboard: ']

    private static final String DAY_VIEW = 'dashboard: the day view adds the ledger lines\' own tokensByModel, ' +
    'snapshots the fold already produced, over JSON with no domain types (the one exemption D10 names)'

    private static final String KIND = '(?:input|output|cacheCreation|cacheRead)'

    /** A file that names a usage type; the running-total rule reads only these. */
    private static final String USAGE_TYPE = /\b(?:TokenUsage|LedgerTokenUsage|ModelUsage|UsageSnapshot)\b/

    static final List<Map> RULES = [
        [name: 'plus on a usage value',
            pattern: /(?:Token|Executor|Model)Usage::plus|\bTokenUsage\s*\.\s*plus\s*\(|\b\w*[Uu]sage\w*(?:\(\))?\s*\.\s*plus\s*\(/,
            allowed: [:]],
        [name: 'one token kind added across two values',
            pattern: ~('\\.(' + KIND + ')\\s*\\(\\s*\\)\\s*\\+\\s*\\w+\\s*\\.\\s*\\1\\s*\\(' +
            '|addExact\\s*\\(\\s*\\w+\\s*\\.\\s*(' + KIND + ')\\s*\\(\\s*\\)\\s*,\\s*\\w+\\s*\\.\\s*\\2\\s*\\(' +
            '|\\+=\\s*[\\w.]*\\.' + KIND + '\\s*\\('),
            allowed: [(OWNER): 'the owner', (AGGREGATOR): DAY_VIEW]],
        [name: 'a merge that sums',
            pattern: /tokensByModel\(\)\s*\.\s*merge\s*\(|\.merge\s*\([^;]*(?<!Integer)(?<!Long)::(?:sum|plus|add|addExact)\b/,
            allowed: [(OWNER): 'the owner', (AGGREGATOR): DAY_VIEW]],
        [name: 'a cost addition',
            pattern: /\b(?:\w*[cC]ost\w*|priced)(?:\s*\(\s*\))?\s*\.\s*add\s*\(|\.add\s*\(\s*[\w.]*(?:costUsd|priced)\s*\(\s*\)|BigDecimal::add/,
            allowed: [(OWNER): 'the owner']],
        [name: 'a running total of tokens',
            pattern: /\b(\w+)\s*=\s*Math\s*\.\s*addExact\s*\(\s*\1\s*,/,
            usageFilesOnly: true,
            allowed: [(AGGREGATOR): DAY_VIEW]],
    ]

    private static final Map STAGE_RULE = [name: 'a jq reduce over tokens',
        pattern: /\breduce\b[^\n]*(?i:token|bymodel)/,
        allowed: [:]]

    // FR17, G5: each sum lives only in the owner and its named exemptions, each reached and still matching
    def "FR17, G5: #rule.name lives only in its allowlisted files"() {
        given:
        def tree = productionCode()

        expect: 'the scan really reached the tree'
        tree.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        tree.containsKey(OWNER)

        and: 'every allowlisted file is reached and still matches, so a removed sum drops its row'
        rule.allowed.keySet().findAll {
            !tree.containsKey(it) || !matches(rule, tree[it])
        }.toList() == []

        and: 'no other file matches'
        offenders(rule, tree) == []

        where:
        rule << RULES
    }

    // G5, M5, M6: the stage scripts carry no jq aggregation of tokens; report.sh posts what
    //     gnomish usage --markdown prints (design D16)
    def "G5, M5, M6: no script under .gnomish/stages reduces tokens with jq"() {
        given:
        def scripts = stageScripts()

        expect: 'the scan reached the stage scripts'
        scripts.size() >= 10

        scripts.containsKey('.gnomish/stages/usage-report/report.sh')

        and: 'no script matches'
        offenders(STAGE_RULE, scripts) == []
    }

    // G5: an exemption names its reason, so the list cannot grow silently
    def "G5: every exemption other than the owner gives a reason the list accepts"() {
        expect:
        (RULES + [STAGE_RULE]).collectMany { rule ->
            rule.allowed.findAll { path, reason ->
                path != OWNER && !REASONS.any { reason.startsWith(it) }
            }.keySet() as List
        } == []
    }

    // The detector: every spelling a sum can take is found, a counter or a mention is not
    def "the detector: #shape"() {
        expect:
        RULES.findAll {
            matches(it, RepoSourceTree.stripComments(source))
        }*.name == names

        where:
        shape | source || names
        'a method reference to plus' | 'm.merge(k, v, TokenUsage::plus);' || [
            'plus on a usage value',
            'a merge that sums'
        ]
        'plus on a usage' | 'var t = usage.plus(other);' || ['plus on a usage value']
        'plus on a usage accessor' | 'var t = round.executorUsage().plus(x);' || ['plus on a usage value']
        'a field-wise sum' | 'new TokenUsage(a.input() + b.input(), 0, 0, 0);' || [
            'one token kind added across two values'
        ]
        'an exact field-wise sum' | 'Math.addExact(a.cacheRead(), b.cacheRead());' || [
            'one token kind added across two values'
        ]
        'an accumulated kind' | 'sum[0] += tokens.input();' || [
            'one token kind added across two values'
        ]
        'a merge on the token map' | 'usage.tokensByModel().merge(k, v, f);' || ['a merge that sums']
        'a merge with a sum' | 'totals.merge(k, v, LedgerAggregator::sum);' || ['a merge that sums']
        'a cost addition' | 'var c = first.costUsd().add(second.costUsd());' || ['a cost addition']
        'a priced addition' | 'priced = priced.add(cost);' || ['a cost addition']
        'a reduce over costs' | 'costs.stream().reduce(BigDecimal.ZERO, BigDecimal::add);' || ['a cost addition']
        'a running total in a usage file' | 'LedgerTokenUsage u; long t = 0; t = Math.addExact(t, total(u));' || ['a running total of tokens']
        'a running total elsewhere' | 'long t = 0; t = Math.addExact(t, x);' || []
        'a counter merge' | 'counts.merge(k, 1, Integer::sum);' || []
        'one value\'s kinds side by side' | 'long cache = summed.cacheCreation() + summed.cacheRead();' || []
        'a duration plus' | 'var w = wallTime.plus(other);' || []
        'a list add' | 'entries.add(entry);' || []
        'a mention in a comment' | '// a.input() + b.input()\nvar x = 1;' || []
    }

    def "the stage-script detector: #shape"() {
        expect:
        matches(STAGE_RULE, source) == found

        where:
        shape | source || found
        'a reduce over a token map' | 'reduce (.[] | .tokensByModel) as $t ({}; add(.; $t))' || true
        'a reduce over byModel' | 'jq \'reduce .usage[].byModel as $m ({}; . + $m)\'' || true
        'a reduce over stage names' | 'reduce .[] as $s ([]; . + [$s])' || false
        'a command run' | 'gnomish usage --markdown "$TASK"' || false
    }

    /** Every production source by its relative path, comments stripped. */
    private static Map<String, String> productionCode() {
        RepoSourceTree.productionSources().collectEntries {
            [(RepoSourceTree.relative(it)): RepoSourceTree.code(it)]
        }
    }

    /** Every file under {@code .gnomish/stages} by its relative path, as written. */
    private static Map<String, String> stageScripts() {
        def root = RepoSourceTree.repoRoot()
        Files.walk(root.resolve('.gnomish/stages')).withCloseable { paths ->
            paths.filter { Files.isRegularFile(it) }.toList().collectEntries {
                [(root.relativize(it).toString()): Files.readString(it)]
            }
        }
    }

    private static List<String> offenders(Map rule, Map<String, String> textByPath) {
        textByPath.findAll { path, text ->
            matches(rule, text) && !rule.allowed.containsKey(path)
        }.keySet().sort()
    }

    private static boolean matches(Map rule, String text) {
        (!rule.usageFilesOnly || text =~ USAGE_TYPE) && text =~ rule.pattern
    }
}
