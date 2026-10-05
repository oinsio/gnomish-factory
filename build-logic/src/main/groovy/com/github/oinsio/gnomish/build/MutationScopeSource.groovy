package com.github.oinsio.gnomish.build

import java.nio.charset.StandardCharsets
import javax.inject.Inject
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations

/**
 * The one place the build decides what PIT mutates (D1 of scope-pit-locally): every module script,
 * the {@code pitestAll} aggregate and the CI workflows consume the {@link MutationScope} it
 * returns and compute no diff of their own (FR5).
 *
 * <p><b>Mode (D5).</b> {@code pitScope} absent → {@link MutationScope.Mode#BRANCH}; {@code all},
 * or a {@code pitestAll} task in the requested task list → {@link MutationScope.Mode#ALL}; any
 * other value → {@link MutationScope.Mode#EXPLICIT} with the comma-separated globs. Only branch
 * mode runs {@code git}.
 *
 * <p><b>Branch mode runs three {@code git} processes (D2, D3, NFR-P1)</b>, from the repository
 * root:
 * <ol>
 *   <li>{@code git rev-parse refs/heads/main...HEAD HEAD^ --symbolic-full-name HEAD} — {@code HEAD},
 *       the ref, the merge base (printed negated), {@code HEAD}'s first parent and the current
 *       branch's full name in one process. On the branch {@code main} itself the base is the first
 *       parent, so a squash or merge commit's own changes are mutated (FR2). The trigger is the
 *       branch name, never "merge base equals {@code HEAD}": a fresh branch with no commits of its
 *       own has that too, and keeps {@code HEAD} as its base so only its uncommitted work is
 *       scoped (D3). If {@code main} does not resolve, the same
 *       call for {@code refs/remotes/origin/main} follows — the fourth process a CI checkout,
 *       which has no local {@code main}, always spends.</li>
 *   <li>{@code git diff --name-status --no-renames <base>} — the working tree against the base,
 *       so committed, staged and unstaged edits are all in it; a rename is a deletion plus an
 *       addition, which {@link ChangedSources} classifies.</li>
 *   <li>{@code git ls-files --others --exclude-standard} — untracked files: the agent never
 *       commits, so a new class is untracked for the whole change.</li>
 * </ol>
 * Any failure — no {@code main} or {@code origin/main}, no merge base, a root commit, no
 * {@code git}, not a checkout — yields {@link MutationScope.Mode#ALL} with the reason: a scope
 * the build cannot justify is wider, never narrower (NFR-R1). Widening by test and fixture
 * changes is {@link ChangedSources}'s (D4).
 *
 * <p><b>Insulation (NFR-R4).</b> Every argv carries {@code -c core.quotePath=false} (non-ASCII
 * paths arrive verbatim, decoded as UTF-8) and {@code -c core.excludesFile=} (an operator's global
 * ignore list cannot hide an untracked class — the repository's own {@code .gitignore} still
 * applies); every process runs with {@code GIT_OPTIONAL_LOCKS=0}, so the diff's index refresh takes
 * no {@code index.lock} and an agent's {@code git status} beside the build is never refused.
 *
 * <p><b>Why a {@code ValueSource} (NFR-R2).</b> {@code org.gradle.configuration-cache} is on: a
 * plain configuration-time read would be replayed from the cache, while Gradle re-obtains a
 * {@code ValueSource} on every build and discards the cached configuration when the value moved —
 * so an edit between two builds re-resolves the scope. {@link HardwareSpec} is the precedent.
 *
 * <p>Implements FR1, FR2, FR4, FR5, NFR-P1, NFR-R1, NFR-R2, NFR-R4 of scope-pit-locally.
 */
abstract class MutationScopeSource implements ValueSource<MutationScope, Parameters> {

    static final String WHOLE_TREE = 'all'
    static final String WHOLE_TREE_TASK = 'pitestAll'

    private static final List<String> INSULATION = ['-c', 'core.quotePath=false', '-c', 'core.excludesFile=']
    private static final String DEFAULT_BRANCH = 'refs/heads/main'
    private static final List<Map<String, String>> BASE_REFS = [
            [ref: 'refs/heads/main', name: 'main'],
            [ref: 'refs/remotes/origin/main', name: 'origin/main'],
    ]

    interface Parameters extends ValueSourceParameters {
        /** The repository root — the root project directory. */
        DirectoryProperty getRepositoryRoot()

        /** Module project path → its directory relative to the root ({@code ''} for the root). */
        MapProperty<String, String> getModuleDirs()

        /** The {@code pitScope} property, if given. */
        Property<String> getRequested()

        /** {@code gradle.startParameter.taskNames}: requesting {@code pitestAll} implies ALL. */
        ListProperty<String> getTaskNames()

        /**
         * The {@code git} command; {@code git} on the daemon's {@code PATH} unless set. Set only by
         * {@code MutationScopeFunctionalSpec}, to a wrapper that records each invocation — a
         * {@code PATH} entry cannot do it, as the JDK resolves the executable against the daemon's
         * own {@code PATH}, not the environment a build is given.
         */
        Property<String> getGitExecutable()
    }

    @Inject
    abstract ExecOperations getExecOperations()

    @Override
    MutationScope obtain() {
        def requested = parameters.requested.orNull?.trim()
        if (parameters.taskNames.get().any { it.tokenize(':').last() == WHOLE_TREE_TASK }) {
            return MutationScope.all("${WHOLE_TREE_TASK} requested".toString())
        }
        if (requested == WHOLE_TREE) {
            return MutationScope.all("-PpitScope=${WHOLE_TREE}".toString())
        }
        if (requested != null) {
            return MutationScope.explicit(requested.split(',').collect { it.trim() }.findAll { !it.isEmpty() })
        }
        branch()
    }

    protected MutationScope branch() {
        def base = BASE_REFS.findResult { resolveBase(it) }
        if (base == null) {
            return MutationScope.all('no scope base: not a git checkout, no main or origin/main, no merge base with HEAD, or HEAD has no parent')
        }
        def diff = git('diff', '--name-status', '--no-renames', base.sha)
        def untracked = git('ls-files', '--others', '--exclude-standard')
        if (diff == null || untracked == null) {
            return MutationScope.all("no scope: git could not list the changes since ${base.sha}".toString())
        }
        def entries = diff.collect { it.split('\t', 2) }.findAll { it.length == 2 }
        def present = entries.findAll { it[0] != 'D' }.collect { it[1] } + untracked
        def deleted = entries.findAll { it[0] == 'D' }.collect { it[1] }
        MutationScope.branch(base.sha, base.origin, ChangedSources.classify(present, deleted, parameters.moduleDirs.get()))
    }

    /** {@code [sha, origin]} of the scope base against {@code candidate}, or null if it has none. */
    protected Map<String, String> resolveBase(Map<String, String> candidate) {
        def lines = git('rev-parse', "${candidate.ref}...HEAD".toString(), 'HEAD^', '--symbolic-full-name', 'HEAD')
        if (lines == null) {
            return null
        }
        // `A...B` prints B, A, then each merge base negated; then HEAD^, then HEAD's full name
        // (`HEAD` itself when detached).
        def positives = lines.findAll { !it.startsWith('^') }
        def mergeBase = lines.find { it.startsWith('^') }?.substring(1)
        if (mergeBase == null || positives.size() != 4) {
            return null
        }
        def firstParent = positives[2]
        def branch = positives[3]
        branch == DEFAULT_BRANCH
                ? [sha: firstParent, origin: 'first parent of HEAD on main']
                : [sha: mergeBase, origin: "merge-base ${candidate.name}".toString()]
    }

    /** The command's stdout lines, or null when it failed or {@code git} could not be started. */
    protected List<String> git(String... args) {
        def out = new ByteArrayOutputStream()
        try {
            def result = execOperations.exec { spec ->
                spec.workingDir = parameters.repositoryRoot.get().asFile
                spec.commandLine([parameters.gitExecutable.getOrElse('git'), *INSULATION, *args])
                spec.environment('GIT_OPTIONAL_LOCKS', '0')
                spec.standardOutput = out
                spec.errorOutput = OutputStream.nullOutputStream()
                spec.ignoreExitValue = true
            }
            result.exitValue == 0 ? out.toString(StandardCharsets.UTF_8).readLines().findAll { !it.isEmpty() } : null
        } catch (RuntimeException ignored) {
            null // no git on PATH, or no such directory: NFR-R1 — the caller widens
        }
    }
}
