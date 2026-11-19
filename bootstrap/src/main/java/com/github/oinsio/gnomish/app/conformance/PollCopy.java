package com.github.oinsio.gnomish.app.conformance;

import com.github.oinsio.gnomish.app.describe.ProgramChannel;
import com.github.oinsio.gnomish.executorcontract.Role;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.Consumer;
import org.jspecify.annotations.NullMarked;

/**
 * The working copy the polls of one pending case name (design D11 of
 * migrate-verify-to-executor-contract as amended 2026-11-14): a directory apart from {@code start}'s,
 * at {@code <scratch>/poll/<case>}, so its path still ends in {@code /<case>}. Its content follows the
 * role the case ran in, as the engine's poll gets it: for {@code verdict} and {@code decision} the
 * content before {@code start} — a fresh box at the attempt commit — and for {@code transform} the files
 * of {@code start}'s working copy at the poll without its {@code .git} — the pending round commit,
 * which carries {@code start}'s writes. What a poll of the key needs therefore lives outside the
 * working copy, or the candidate fails the case.
 *
 * <p>Implements FR11, FR12 of migrate-verify-to-executor-contract.
 */
@NullMarked
final class PollCopy {

    /** The directory under the scratch root that holds every case's poll copy: named after the operation. */
    static final String DIRECTORY = ProgramChannel.POLL;

    private static final String GIT = ".git";

    private PollCopy() {}

    /**
     * Creates the poll copy of {@code kase}.
     *
     * @param scratchRoot the directory the case's working copies are made in; never null
     * @param kase the case; never null
     * @param role the role the case's {@code start} ran in; never null
     * @param startCopy the working copy {@code start} ran in; never null
     * @param beforeStart what made {@code start}'s working copy what it was before {@code start}, run
     *     over the new, empty directory for {@code verdict} and {@code decision}; never null
     * @return the poll copy; never null
     * @throws UncheckedIOException if the copy could not be made, or already exists
     */
    static Path create(Path scratchRoot, KitCase kase, Role role, Path startCopy, Consumer<Path> beforeStart) {
        Path copy;
        try {
            copy = Files.createDirectory(
                    Files.createDirectories(scratchRoot.resolve(DIRECTORY)).resolve(kase.word()));
            if (role == Role.TRANSFORM) {
                Files.walkFileTree(startCopy, new FilesWithoutGit(startCopy, copy));
                return copy;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not make the poll copy of case " + kase.word(), e);
        }
        beforeStart.accept(copy);
        return copy;
    }

    /** Copies every file and directory under {@code from} to {@code to}, except {@code from}'s {@code .git}. */
    private static final class FilesWithoutGit extends SimpleFileVisitor<Path> {

        private final Path from;
        private final Path to;

        FilesWithoutGit(Path from, Path to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            if (dir.equals(from.resolve(GIT))) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            Path target = to.resolve(from.relativize(dir).toString());
            if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(target);
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            if (!file.equals(from.resolve(GIT))) {
                Files.copy(file, to.resolve(from.relativize(file).toString()), LinkOption.NOFOLLOW_LINKS);
            }
            return FileVisitResult.CONTINUE;
        }
    }
}
