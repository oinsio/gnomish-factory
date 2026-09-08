package com.github.oinsio.gnomish.adapter.law;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The {@link LawSource} realization that reads law from the law root of the factory clone's
 * working tree (design D12 of add-base-ref-resolution): the git-less in-place mode and manual
 * {@code gnomish run} without {@code --base}, where an uncommitted {@code .gnomish/} edit is
 * meant to be the law so a pipeline author can edit and run.
 *
 * <p><b>Traversal, fail-closed.</b> Every reference is classified by the shared {@link
 * LawPathWalk} over this tree's entries, read from the filesystem without following links, so a
 * symlink entry at any segment is refused with its target unseen — inside the root or not — and a
 * symlinked directory hides everything beneath it. This is exactly the git-objects realization's
 * verdict, from the same walk: one commit is law in every medium or law in none. Unlike a git
 * tree, a working tree can change between the walk and the read; that window is the one reason
 * every resolved-ref path reads git objects instead.
 *
 * <p>Implements FR11 of add-base-ref-resolution.
 */
public final class WorkingTreeLawSource implements LawSource {

    private final Path root;
    private final LawTree tree = this::node;

    /**
     * @param root the law root — the directory root-relative references resolve against
     */
    public WorkingTreeLawSource(Path root) {
        this.root = root.normalize();
    }

    @Override
    public Read read(String ref) {
        return switch (LawPathWalk.walk(root, ref, tree)) {
            case LawPathWalk.Escapes escapes -> new Unreadable("path escapes the configuration root: " + escapes.ref());
            case LawPathWalk.Symlinked symlinked ->
                new Unreadable("a symlink is not a law file at '" + symlinked.segment() + "' under " + root);
            case LawPathWalk.File file -> readFile(root.resolve(file.relative()));
            case LawPathWalk.Absent absent ->
                new Unreadable("no law file at '" + root.resolve(absent.relative()) + "'");
            case LawPathWalk.Directory directory -> notARegularFile(root.resolve(directory.relative()));
            case LawPathWalk.Other other -> notARegularFile(root.resolve(other.relative()));
            case LawPathWalk.Root _ -> notARegularFile(root);
        };
    }

    @Override
    public FileStatus fileStatus(String ref) {
        return LawPathWalk.fileStatus(LawPathWalk.walk(root, ref, tree));
    }

    @Override
    public List<LawEntry> list(String ref) throws IOException {
        return switch (LawPathWalk.walk(root, ref, tree)) {
            case LawPathWalk.Root _ -> entries(root);
            case LawPathWalk.Directory directory -> entries(root.resolve(directory.relative()));
            case LawPathWalk.Escapes _,
                    LawPathWalk.Symlinked _,
                    LawPathWalk.File _,
                    LawPathWalk.Absent _,
                    LawPathWalk.Other _ -> List.of();
        };
    }

    /** What the entry at {@code relative} holds, asked of the filesystem without following links. */
    private LawTree.@Nullable Node node(Path relative) {
        Path path = root.resolve(relative);
        if (Files.isSymbolicLink(path)) {
            return LawTree.Node.SYMLINK;
        }
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return LawTree.Node.FILE;
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return LawTree.Node.DIRECTORY;
        }
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? LawTree.Node.OTHER : null;
    }

    private static Read readFile(Path path) {
        try {
            return new Text(Files.readString(path));
        } catch (IOException e) {
            return new Unreadable(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static Unreadable notARegularFile(Path path) {
        return new Unreadable("not a regular file, so not a law file: '" + path + "'");
    }

    private static List<LawEntry> entries(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(WorkingTreeLawSource::entry).toList();
        }
    }

    /** One listing entry, classified without following links so a symlink is never read as its target. */
    private static LawEntry entry(Path path) {
        String name = path.getFileName().toString();
        if (Files.isSymbolicLink(path)) {
            return new LawEntry(name, LawEntry.Kind.OTHER);
        }
        if (Files.isRegularFile(path)) {
            return new LawEntry(name, LawEntry.Kind.FILE);
        }
        if (Files.isDirectory(path)) {
            return new LawEntry(name, LawEntry.Kind.DIRECTORY);
        }
        return new LawEntry(name, LawEntry.Kind.OTHER);
    }
}
