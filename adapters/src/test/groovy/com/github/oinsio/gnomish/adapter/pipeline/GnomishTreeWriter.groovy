package com.github.oinsio.gnomish.adapter.pipeline

import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes a file into a {@code .gnomish/} tree rooted at a {@code @TempDir}, creating
 * the parent directories on the way. Every pipeline-loading fixture builds its tree
 * programmatically rather than committing a sample tree (design D8, {@code @TempDir}
 * strategy), so these two primitives are the whole of what the fixture traits share.
 *
 * <p>The second primitive is the read-only counterpart: every seam of the loader is
 * specified as a pure read (NFR-R1), and each such spec proves it the same way — take a
 * path-to-content map of the tree before the call, take it again after, compare.
 *
 * <p>Supports M2 / UX1 and NFR-R1 of load-pipeline-config.
 */
trait GnomishTreeWriter {

    abstract Path getRoot()

    /** Writes {@code text} to {@code relative} under the root, creating parent directories. */
    void write(String relative, String text) {
        Path target = getRoot().resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
    }

    /** Every regular file under the root as {@code relative path -> content}, for read-only assertions. */
    Map<String, String> snapshot() {
        Path base = getRoot()
        Map<String, String> files = [:]
        Files.walk(base).withCloseable { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { p ->
                files.put(base.relativize(p).toString(), Files.readString(p))
            }
        }
        files
    }
}
