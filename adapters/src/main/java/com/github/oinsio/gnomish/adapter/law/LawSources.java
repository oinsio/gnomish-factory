package com.github.oinsio.gnomish.adapter.law;

import com.github.oinsio.gnomish.app.LawBinding;
import com.github.oinsio.gnomish.app.UsageException;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import com.github.oinsio.gnomish.gitobjects.GitObjectsException;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one place a {@link LawBinding} — the use-case layer's medium-blind statement of which tree
 * an invocation's law lives in — becomes a {@link LawSource} (design D12 of
 * add-base-ref-resolution). The binding owns the law root and the repository; this factory only
 * picks the realization and peels the binding's revision to a commit: the layer above states which
 * law it wants, this one knows how to open it.
 *
 * <p><b>The revision is peeled once, and the pin is the peeled id.</b> The commit is handed back
 * beside the source as a typed {@link ObjectId} that the external-check pin guard compares against
 * as is — it does no {@code rev-parse} of its own — so the frozen law and the pin name the same
 * SHA by construction. The pre-D12 {@code "HEAD"} pin could not give that guarantee: law and pin
 * then resolved the clone's checkout independently, and a checkout moving between the two reads
 * made the guard compare an attempt against law the run never froze.
 *
 * <p>Implements FR11, M5 of add-base-ref-resolution.
 */
public final class LawSources {

    private static final Logger log = LoggerFactory.getLogger(LawSources.class);

    private LawSources() {}

    /**
     * One invocation's opened law: where its files are read from, and the commit the pin guard
     * compares an attempt's pinned definition files against.
     *
     * @param source the opened realization for the binding's medium
     * @param lawCommit the peeled law commit — the revision's own commit where one was bound, the
     *     checkout's commit under a working-tree binding; {@code null} only when a working-tree
     *     binding's root resolves no checkout at all (an in-place workspace that is no repository),
     *     in which case the pin guard degrades fail-closed
     */
    public record BoundLaw(LawSource source, @Nullable ObjectId lawCommit) {}

    /**
     * Opens the law {@code binding} names.
     *
     * <p>Implements FR11 of add-base-ref-resolution.
     *
     * @param binding which repository and tree this invocation's law is bound to
     * @param gitObjects the bare-object reader of the binding's repository: a revision binding reads
     *     its law through it, a working-tree binding only peels the checkout for the pin
     * @return the opened source and the peeled law commit; never null
     * @throws UsageException when the binding names a revision the repository resolves to no commit
     */
    public static BoundLaw open(LawBinding binding, GitObjects gitObjects) {
        return switch (binding) {
            case LawBinding.WorkingTree workingTree ->
                new BoundLaw(new WorkingTreeLawSource(workingTree.lawRoot()), checkoutOf(gitObjects));
            case LawBinding.AtRevision atRevision -> atCommit(atRevision.revision(), gitObjects);
        };
    }

    /**
     * The bare-object reader of the repository at {@code repositoryRoot} — the one spelling of
     * "where a binding's git objects live", shared by every opener of a binding so the run's law and
     * the per-task definition read the same object store.
     *
     * @param repositoryRoot the repository root a {@link LawBinding} carries; never null
     * @return the reader over that repository's {@code .git}; never null
     */
    public static GitObjects gitObjectsOf(Path repositoryRoot) {
        return GitObjects.open(
                repositoryRoot.resolve(".git"), Path.of(Objects.requireNonNull(System.getProperty("java.io.tmpdir"))));
    }

    /** The git-objects realization at the commit {@code revision} peels to, with that commit as the pin. */
    private static BoundLaw atCommit(String revision, GitObjects gitObjects) {
        ObjectId lawCommit = gitObjects
                .resolveRef(revision)
                .orElseThrow(() -> new UsageException("cannot bind the pipeline law: '" + revision
                        + "' resolves to no commit in the factory clone — the law of a task is read from its"
                        + " base's own commit, never from the clone's working tree"));
        return new BoundLaw(new GitObjectsLawSource(gitObjects, lawCommit, LawBinding.LAW_ROOT), lawCommit);
    }

    /**
     * The checkout's commit under a working-tree binding — the pin of a manual run without {@code
     * --base} — or {@code null} where the root is no repository: the in-place mode's workspace need
     * not be one, and git cannot even be launched against a missing object store. That is the
     * expected outcome being classified, not a fault, so it is noted at DEBUG and the pin guard is
     * left to refuse a pinned check on its own.
     */
    private static @Nullable ObjectId checkoutOf(GitObjects gitObjects) {
        try {
            return gitObjects.resolveRef(GitObjects.HEAD).orElse(null);
        } catch (GitObjectsException e) {
            // throwable-not-subject: a workspace without an object store is the classified outcome;
            //     the pin guard reports the missing commit at its own point of use.
            log.debug("working-tree law binding resolves no checkout; the external-check pin has no commit");
            return null;
        }
    }
}
