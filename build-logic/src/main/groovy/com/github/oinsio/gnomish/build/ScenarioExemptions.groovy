package com.github.oinsio.gnomish.build

/**
 * The gate's exemption list (FR5 of tighten-spec-contract, design D5, D9): every exemption file
 * under {@code config/spec-contract-exemptions/}, one per capability, each read by {@link
 * ExemptionFile}, the one parser of the form. An entry names a scenario of its file's capability
 * and the mechanism that verifies it instead of a test — a CI workflow, a release script, or
 * {@code pending-test: <change>} for a scenario whose test a named later change writes. The list is
 * asserted reached: a file naming no capability of a main spec, or an entry naming no scenario of
 * its capability, is stale and fails, so the list stays the list of real exceptions.
 *
 * <p>A spec-id scope judges only the entries of in-scope files; a finding about a file itself (its
 * name, its header) is reported by every run, since such a file names no scope it could fall out of.
 *
 * <p>Implements FR5 of tighten-spec-contract.
 */
final class ScenarioExemptions {

    static final String PENDING = ExemptionFile.PENDING

    /** Well-formed entries of in-scope files, scenario key ({@code <spec-id> / <title>}) to mechanism. */
    final Map<String, String> entries = [:]

    final List<SpecContractFinding> findings = []

    private final List<ExemptionFile> files

    private ScenarioExemptions(List<ExemptionFile> files) {
        this.files = files
    }

    /** Reads {@code files}; {@code inScope} decides which capabilities' entries are judged. */
    static ScenarioExemptions read(File base, Collection<File> files, Closure<Boolean> inScope) {
        List<ExemptionFile> parsed = files.sort(false) { it.name }.collect { ExemptionFile.read(base, it) }
        ScenarioExemptions exemptions = new ScenarioExemptions(parsed.findAll { it.specId != null && inScope(it.specId) })
        parsed.each { exemptions.findings.addAll(it.fileFindings) }
        exemptions.files.each { ExemptionFile file ->
            exemptions.findings.addAll(file.lineFindings)
            file.entries.each { exemptions.entries[file.specId + ' / ' + it.title] = it.mechanism }
        }
        exemptions
    }

    /**
     * Fails every in-scope file whose spec id no main spec has ({@code specIds}), and every entry
     * whose scenario is not in {@code existing}: a stale line to remove.
     */
    void assertReached(Set<String> specIds, Set<String> existing) {
        files.each { ExemptionFile file ->
            if (!(file.specId in specIds)) {
                file.entries.each { entries.remove(file.specId + ' / ' + it.title) }
                findings << file.finding(file.headerLine, "unknown spec id '${file.specId}': no main spec has it",
                        'correct the header to the capability path under openspec/specs, or delete the file')
                return
            }
            Collection<String> sameSpec = existing.findAll { it.startsWith(file.specId + ' / ') }
            file.entries.findAll { !((file.specId + ' / ' + it.title) in existing) }.each { ExemptionFile.Entry entry ->
                String nearest = NearestTitle.of(file.specId + ' / ' + entry.title, sameSpec)
                findings << file.finding(entry.line,
                        "stale exemption '${file.specId} / ${entry.title}': no main spec holds this scenario",
                        'remove the line' + (nearest == null ? '' : ", or correct it to the scenario it meant — the nearest is '${nearest}'"))
            }
        }
    }

    int pendingCount(Set<String> exempt) {
        exempt.count { entries[it]?.startsWith(PENDING) } as int
    }
}
