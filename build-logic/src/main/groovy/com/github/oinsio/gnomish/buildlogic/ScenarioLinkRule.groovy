package com.github.oinsio.gnomish.buildlogic

import groovy.transform.Immutable

/**
 * The scenario-to-test link (FR5 of tighten-spec-contract, design D4), in both directions: every
 * scenario of a main spec is named by a {@code // Scenario: <spec-id> / <title>} marker in some
 * test source or by an exemption entry, and every marker names a scenario a main spec holds, or
 * one under an ADDED or MODIFIED requirement of an unarchived delta (D7 of
 * fix-terminal-receipt-convergence). A {@code // Scenario:} line not in that form is a malformed
 * marker.
 *
 * <p>Only main specs are linked: a scenario of an unarchived delta need not be named (proposal NG6
 * of tighten-spec-contract), it may only be. A main-spec requirement that an unarchived delta
 * REMOVES (same requirement name, same spec id; a delta under {@code archive/} is never read) is
 * retired, and its scenarios need no link: the change that removes them is the record of their
 * retirement (D7 extension of fix-terminal-receipt-convergence). Retirement only relaxes the
 * gate: a retired scenario stays resolvable, so a marker naming it is not dangling, and an
 * exemption line naming it is not stale; the change deletes those leftovers itself, and the
 * archive's own gate run reports any it forgot, once the scenario has left the main spec.
 *
 * <p>A spec-id scope narrows the run for a per-task
 * check: only the scenarios, markers and exemption entries of those spec ids are judged, and a
 * malformed marker, which names no spec id, only by an unscoped run.
 *
 * <p>Implements FR5 and NFR-O1 of tighten-spec-contract; FR6 of fix-terminal-receipt-convergence.
 */
final class ScenarioLinkRule {

    private ScenarioLinkRule() {
    }

    /** What the link rule found, and the counts of the summary line. */
    @Immutable
    static class Verdict {
        List<SpecContractFinding> findings
        int checked
        int linked
        int exempt
        int pending
    }

    static Verdict judge(List<SpecFile> mains, List<SpecFile> deltas, Map<String, ScenarioMarkerReader.Markers> markers,
            ScenarioExemptions exemptions, Set<String> scope) {
        Closure<Boolean> inScope = { String specId -> scope.isEmpty() || specId in scope }
        Set<String> removed = deltas.collectMany { SpecFile delta ->
            delta.document.requirements.findAll { (it.section ?: '').startsWith('REMOVED') }.collect { delta.specId + ' / ' + it.name }
        } as Set<String>
        Map<String, List<Map>> scenarios = [:].withDefault { [] }
        mains.findAll { inScope(it.specId) }.each { SpecFile spec ->
            spec.document.requirements.findAll { !((spec.specId + ' / ' + it.name) in removed) }.each { requirement ->
                requirement.scenarios.each {
                    scenarios[spec.specId + ' / ' + it.title] << [path: spec.path, line: it.line]
                }
            }
        }
        Set<String> existing = keys(mains) { true }
        Set<String> resolvable = existing + keys(deltas) { String section -> section.startsWith('ADDED') || section.startsWith('MODIFIED') }
        List<SpecContractFinding> findings = []
        Set<String> linked = [] as Set
        markers.each { String path, ScenarioMarkerReader.Markers found ->
            found.markers.findAll { inScope(it.specId) }.each { marker ->
                String key = marker.specId + ' / ' + marker.title
                if (key in resolvable) {
                    linked << key
                } else {
                    findings << dangling(path, marker.line, key, resolvable)
                }
            }
            found.malformed.findAll { scope.isEmpty() }.each {
                findings << new SpecContractFinding(path: path, line: it.line, rule: SpecContractFinding.MALFORMED_MARKER,
                        what: "a scenario marker not in the link form: '${it.text}'".toString(),
                        fix: "write `${ScenarioMarkerReader.PREFIX} <spec-id> / <title>`, the title verbatim, "
                                + 'or reword the comment so it does not start with the marker')
            }
        }
        exemptions.assertReached(mains*.specId as Set<String>, existing)
        findings.addAll(exemptions.findings)
        Set<String> exempt = scenarios.keySet().findAll { !(it in linked) && exemptions.entries.containsKey(it) }
        scenarios.findAll { key, places -> !(key in linked) && !(key in exempt) }.each { key, places ->
            places.each { place ->
                findings << new SpecContractFinding(path: place.path, line: place.line,
                        rule: SpecContractFinding.UNLINKED_SCENARIO,
                        what: "scenario '${key}' is named by no test marker and no exemption".toString(),
                        fix: "write `${ScenarioMarkerReader.PREFIX} ${key}` above the feature that exercises it, "
                                + 'or add an exemption entry naming the mechanism that verifies it')
            }
        }
        new Verdict(findings: findings, checked: scenarios.size(),
                linked: scenarios.keySet().count { it in linked } as int,
                exempt: exempt.size(), pending: exemptions.pendingCount(exempt))
    }

    /** The {@code <spec-id> / <title>} keys of the scenarios under the requirements whose section {@code admits}. */
    private static Set<String> keys(List<SpecFile> specs, Closure<Boolean> admits) {
        specs.collectMany { SpecFile spec ->
            spec.document.requirements.findAll { admits(it.section ?: '') }
                    .collectMany { r -> r.scenarios.collect { spec.specId + ' / ' + it.title } }
        } as Set<String>
    }

    private static SpecContractFinding dangling(String path, int line, String key, Set<String> existing) {
        String specId = key.substring(0, key.indexOf(' / '))
        Collection<String> sameSpec = existing.findAll { it.startsWith(specId + ' / ') }
        String nearest = NearestTitle.of(key, sameSpec ?: existing)
        new SpecContractFinding(path: path, line: line, rule: SpecContractFinding.DANGLING_MARKER,
                what: "marker names no scenario of a main spec or of an unarchived delta: '${key}'".toString(),
                fix: nearest == null ? 'remove the marker, or write the scenario it names'
                        : "point it at the scenario it exercises — the nearest existing one is '${nearest}' — or remove it")
    }
}
