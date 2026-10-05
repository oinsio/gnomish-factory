package com.github.oinsio.gnomish.build

/**
 * Maps a branch's changed paths to what the mutation gate must mutate, per module — the widening
 * rules of D4 of scope-pit-locally, kept beside the owner rather than in the module script so the
 * whole "what changed → what to mutate" rule lives in one place.
 *
 * <ul>
 *   <li>A present {@code .java} file under {@code <module>/src/main/java/} is that module's changed
 *       class ({@code package-info} is not a class). A deleted one contributes nothing (FR3).</li>
 *   <li>Any change under {@code <module>/src/test/} — a deletion included, since deleting a killing
 *       spec weakens the gate as surely as editing it — widens that module to its whole production
 *       tree: specs are named by capability, not by the class they kill, so no name rule could map
 *       a spec to its classes (FR3).</li>
 *   <li>Any change under {@code test-fixtures/src/} widens every module: the shared fixtures are on
 *       every module's test classpath, and a weakened fake can hide a survivor anywhere (FR3).</li>
 * </ul>
 *
 * <p>A path is matched to the module whose directory is the LONGEST prefix ahead of the source
 * root, not by path depth: modules nest ({@code sandbox/core}, {@code gnomish-plugin-api/sample}
 * inside {@code gnomish-plugin-api}). A path under no known module — a build script, a doc, the
 * {@code build-logic} included build — contributes nothing.
 *
 * <p>Implements FR3 of scope-pit-locally.
 */
final class ChangedSources {

    static final String TEST_CHANGE = 'test change'
    static final String SHARED_FIXTURE = 'shared fixture'

    private static final String MAIN_JAVA = 'src/main/java/'
    private static final String TEST = 'src/test/'
    private static final String SHARED_FIXTURE_SOURCES = 'test-fixtures/src/'

    /** Module project path → changed production class names. */
    final Map<String, Set<String>> classes
    /** Module project path → widening reason. */
    final Map<String, String> widened

    private ChangedSources(Map<String, Set<String>> classes, Map<String, String> widened) {
        this.classes = classes
        this.widened = widened
    }

    /**
     * @param present paths that exist in the working tree (added, modified, untracked)
     * @param deleted paths the working tree no longer has
     * @param moduleDirs module project path → its directory relative to the repository root
     *     ({@code ''} for the root project)
     */
    static ChangedSources classify(Collection<String> present, Collection<String> deleted,
                                   Map<String, String> moduleDirs) {
        Map<String, Set<String>> classes = new TreeMap<>()
        Map<String, String> widened = new TreeMap<>()
        def touched = (present + deleted)
        if (touched.any { it.startsWith(SHARED_FIXTURE_SOURCES) }) {
            moduleDirs.keySet().each { widened[it] = SHARED_FIXTURE }
        }
        touched.each { path ->
            def test = owner(path, TEST, moduleDirs)
            if (test != null) {
                widened.putIfAbsent(test.module, TEST_CHANGE)
            }
        }
        present.each { path ->
            def main = owner(path, MAIN_JAVA, moduleDirs)
            if (main != null && main.rest.endsWith('.java') && !main.rest.endsWith('package-info.java')) {
                def fqcn = main.rest[0..-'.java'.length() - 1].replace('/', '.')
                classes.computeIfAbsent(main.module) { new TreeSet<String>() } << fqcn
            }
        }
        new ChangedSources(classes, widened)
    }

    /** The module owning {@code path} under {@code sourceRoot}, and the path below that root. */
    private static Map owner(String path, String sourceRoot, Map<String, String> moduleDirs) {
        def match = moduleDirs
                .collect { module, dir -> [module: module, prefix: dir.isEmpty() ? sourceRoot : "${dir}/${sourceRoot}"] }
                .findAll { path.startsWith(it.prefix as String) }
                .max { (it.prefix as String).length() }
        match == null ? null : [module: match.module, rest: path.substring((match.prefix as String).length())]
    }
}
