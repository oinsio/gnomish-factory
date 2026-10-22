package com.github.oinsio.gnomish.buildlogic

/**
 * Maps a branch's changed paths to the production classes the mutation gate must mutate, per
 * module — kept beside the owner rather than in the module script so the whole "what changed →
 * what to mutate" rule lives in one place. Two rules remain:
 *
 * <ul>
 *   <li>A present {@code .java} file under {@code <module>/src/main/java/} is that module's changed
 *       class ({@code package-info} is not a class).</li>
 *   <li>A deleted one is never in the present list {@link MutationScopeSource} hands over, so it
 *       contributes nothing.</li>
 * </ul>
 *
 * <p>Nothing else widens the scope: a change under a module's {@code src/test/} or under the shared
 * {@code test-fixtures/src/} contributes no class, and the whole tree is the nightly run's (D1 of
 * narrow-branch-mutation-scope, which deletes the widening of D4 of scope-pit-locally).
 *
 * <p>A path is matched to the module whose directory is the LONGEST prefix ahead of the source
 * root, not by path depth: modules nest ({@code sandbox/core}, {@code gnomish-plugin-api/sample}
 * inside {@code gnomish-plugin-api}). A path under no known module — a build script, a doc, the
 * {@code build-logic} included build — contributes nothing.
 *
 * <p>Implements FR3 of scope-pit-locally; FR1, FR2, FR4 of narrow-branch-mutation-scope.
 */
final class ChangedSources {

    private static final String MAIN_JAVA = 'src/main/java/'

    /** Module project path → changed production class names. */
    final Map<String, Set<String>> classes

    private ChangedSources(Map<String, Set<String>> classes) {
        this.classes = classes
    }

    /**
     * @param present paths that exist in the working tree (added, modified, untracked)
     * @param moduleDirs module project path → its directory relative to the repository root
     *     ({@code ''} for the root project)
     */
    static ChangedSources classify(Collection<String> present, Map<String, String> moduleDirs) {
        Map<String, Set<String>> classes = new TreeMap<>()
        present.each { path ->
            def main = owner(path, MAIN_JAVA, moduleDirs)
            if (main != null && main.rest.endsWith('.java') && !main.rest.endsWith('package-info.java')) {
                def fqcn = main.rest[0..-'.java'.length() - 1].replace('/', '.')
                classes.computeIfAbsent(main.module) { new TreeSet<String>() } << fqcn
            }
        }
        new ChangedSources(classes)
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
