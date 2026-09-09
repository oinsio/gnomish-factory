package com.github.oinsio.gnomish.adapter.law;

import com.github.oinsio.gnomish.gitobjects.GitObjects;
import com.github.oinsio.gnomish.gitobjects.GitObjectsException;
import com.github.oinsio.gnomish.gitobjects.MissingObjectException;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import com.github.oinsio.gnomish.gitobjects.TreeEntry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.StringJoiner;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link LawSource} realization that reads law out of bare git objects at one commit — the
 * <em>law commit</em> — with no checkout (design D12 of add-base-ref-resolution). Every path that
 * resolved a ref binds law through this: take, serve, and manual {@code run --base}. The factory
 * clone's working tree, index and {@code HEAD} then play no part in the law, so a task based on
 * {@code release/1.18} reads that branch's instructions however the clone happens to be checked
 * out, and an uncommitted edit in the clone reaches no autonomous task.
 *
 * <p><b>Traversal, fail-closed.</b> Every reference is classified by the shared {@link
 * LawPathWalk} over this tree's entries — read from each parent tree, the only place git records
 * an entry's mode — so a symlink entry at any segment is refused outright, target unseen, and a
 * symlinked directory is refused rather than reported absent. The same refusal covers everything
 * else git can record in a tree — a submodule's pinned commit included.
 *
 * <p>Implements FR11 of add-base-ref-resolution.
 */
public final class GitObjectsLawSource implements LawSource {

    private static final Logger log = LoggerFactory.getLogger(GitObjectsLawSource.class);

    /** The per-file read cap: law files are hand-written text, and a truncated one is never law. */
    public static final long DEFAULT_READ_CAP_BYTES = 1L << 20;

    private final GitObjects gitObjects;
    private final ObjectId lawCommit;
    private final Path root;
    private final long readCapBytes;
    private final LawTree tree = this::node;

    /**
     * @param gitObjects the bare-objects reader of the factory clone
     * @param lawCommit the commit the law is bound to for this invocation
     * @param root the law root as a repository-relative directory path, e.g. {@code .gnomish};
     *     never blank — the repository root is not a law root
     */
    public GitObjectsLawSource(GitObjects gitObjects, ObjectId lawCommit, String root) {
        this(gitObjects, lawCommit, root, DEFAULT_READ_CAP_BYTES);
    }

    /**
     * @param readCapBytes the per-file read cap; a law file over it is unreadable, never truncated
     */
    public GitObjectsLawSource(GitObjects gitObjects, ObjectId lawCommit, String root, long readCapBytes) {
        if (root.isBlank()) {
            throw new IllegalArgumentException("law root must name a directory in the law commit's tree");
        }
        this.gitObjects = gitObjects;
        this.lawCommit = lawCommit;
        this.root = Path.of(root).normalize();
        this.readCapBytes = readCapBytes;
    }

    @Override
    public Read read(String ref) {
        return switch (LawPathWalk.walk(root, ref, tree)) {
            case LawPathWalk.Escapes escapes -> new Unreadable("path escapes the configuration root: " + escapes.ref());
            case LawPathWalk.Symlinked symlinked ->
                new Unreadable(
                        "a symlink is not a law file at '" + repoPath(symlinked.segment()) + "' in " + lawCommit.hex());
            case LawPathWalk.File file -> readBlob(repoPath(file.relative()));
            case LawPathWalk.Absent absent -> noLawFile(repoPath(absent.relative()));
            case LawPathWalk.Directory directory -> notARegularFile(repoPath(directory.relative()));
            case LawPathWalk.Other other -> notARegularFile(repoPath(other.relative()));
            case LawPathWalk.Root _ -> notARegularFile(gitPath(root));
        };
    }

    @Override
    public FileStatus fileStatus(String ref) {
        return LawPathWalk.fileStatus(LawPathWalk.walk(root, ref, tree));
    }

    @Override
    public List<LawEntry> list(String ref) {
        return switch (LawPathWalk.walk(root, ref, tree)) {
            case LawPathWalk.Root _ -> entries(gitPath(root));
            case LawPathWalk.Directory directory -> entries(repoPath(directory.relative()));
            case LawPathWalk.Escapes _,
                    LawPathWalk.Symlinked _,
                    LawPathWalk.File _,
                    LawPathWalk.Absent _,
                    LawPathWalk.Other _ -> List.of();
        };
    }

    /**
     * What the entry at {@code relative} holds — read from its parent tree, which is the only place
     * a git tree records an entry's mode — or {@code null} when the law commit has no such entry.
     */
    private LawTree.@Nullable Node node(Path relative) {
        Path parent = relative.getParent();
        String name = relative.getFileName().toString();
        return listTree(parent == null ? gitPath(root) : repoPath(parent)).stream()
                .filter(entry -> entry.name().equals(name))
                .map(entry -> node(entry.kind()))
                .findFirst()
                .orElse(null);
    }

    private static LawTree.Node node(TreeEntry.Kind kind) {
        return switch (kind) {
            case FILE -> LawTree.Node.FILE;
            case DIRECTORY -> LawTree.Node.DIRECTORY;
            case SYMLINK -> LawTree.Node.SYMLINK;
            case OTHER -> LawTree.Node.OTHER;
        };
    }

    private Read readBlob(String path) {
        try {
            return new Text(new String(gitObjects.readBlob(lawCommit, path, readCapBytes), StandardCharsets.UTF_8));
        } catch (GitObjectsException e) {
            return new Unreadable(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Unreadable noLawFile(String path) {
        return new Unreadable("no law file at '" + path + "' in " + lawCommit.hex());
    }

    private Unreadable notARegularFile(String path) {
        return new Unreadable("not a regular file, so not a law file: '" + path + "' in " + lawCommit.hex());
    }

    private List<LawEntry> entries(String path) {
        return listTree(path).stream().map(GitObjectsLawSource::entry).toList();
    }

    /**
     * The entries of the tree at the repository-relative {@code path}, or an empty listing when the
     * law commit has no tree there — absent, or a blob. "Not a tree" and "an empty tree" hold the
     * same law: none, and {@link MissingObjectException} is git's own name for exactly that
     * classification, so it is the only failure swallowed here.
     *
     * <p>Every other {@link GitObjectsException} is a fault, not a verdict — git could not be
     * launched, the wait was interrupted, an object is corrupt, {@code ls-tree} printed something
     * unparsable — and it propagates. Reading a fault as "the law commit carries no such tree"
     * would be the one fail-open in a fail-closed traversal: an absent listing is law the reader
     * acts on, and an unreachable one is not. The caller above already handles an unchecked
     * {@link GitObjectsException} from this repository, which is what {@code LawSources.open}
     * raises for the same faults while peeling the law commit.
     */
    private List<TreeEntry> listTree(String path) {
        try {
            return gitObjects.listTree(lawCommit, path);
        } catch (MissingObjectException e) {
            // throwable-not-subject: "no tree at this path in the law commit" is the outcome being
            //     classified, not a failure; the verdict it produces is reported at its point of use.
            log.debug("no tree at '{}' in {}: the law commit carries nothing there", path, lawCommit.hex());
            return List.of();
        }
    }

    private static LawEntry entry(TreeEntry entry) {
        return new LawEntry(
                entry.name(),
                switch (entry.kind()) {
                    case FILE -> LawEntry.Kind.FILE;
                    case DIRECTORY -> LawEntry.Kind.DIRECTORY;
                    case SYMLINK, OTHER -> LawEntry.Kind.OTHER;
                });
    }

    /** The repository-relative git path of a root-relative law path. */
    private String repoPath(Path relative) {
        return gitPath(root.resolve(relative));
    }

    /** A {@link Path}'s segments as a git path — always {@code /}-separated, whatever the platform. */
    private static String gitPath(Path path) {
        StringJoiner joiner = new StringJoiner("/");
        for (Path segment : path) {
            joiner.add(segment.toString());
        }
        return joiner.toString();
    }
}
