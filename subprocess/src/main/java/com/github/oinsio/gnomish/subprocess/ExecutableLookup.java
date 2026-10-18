package com.github.oinsio.gnomish.subprocess;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A command's executable resolved as {@code execvp} would, before the launch owner puts its
 * launcher in front (design D19 as amended 2026-10-17 of fix-terminal-receipt-convergence): with
 * the launcher in front, an executable that cannot run no longer makes {@code start()} throw — the
 * launcher starts and its own exec fails — so the owner answers that question first, while no
 * process exists, and the four sites that classify a thrown launch failure keep their meaning.
 *
 * <p>A name holding a {@code /} is a path, relative to the child's working directory; a bare name
 * is searched on the child's {@code PATH}, entry by entry, and only on its absolute entries — an
 * empty or relative entry would name the working directory, which never resolves a bare name
 * (Go's {@code exec.ErrDot}). Either way the candidate must be a regular file the factory may
 * execute. The answer is an absolute path, so what was resolved is what the launcher runs, and no
 * launcher ever parses it as an option of its own.
 *
 * <p>The executable's {@code #!} interpreter chain is then followed as the kernel's {@code
 * binfmt_script} follows it (design D19 as amended 2026-10-17, second): a script whose interpreter
 * is absent made {@code start()} throw before the launcher existed, and behind the launcher it would
 * only exit 127. The file's head is read; the interpreter is the first word after {@code #!}, resolved
 * as a path — relative to the child's working directory when it is relative, as the kernel opens it,
 * and never on {@code PATH} — and checked as the executable was. At most {@value #MAX_HOPS}
 * interpreters are judged; a deeper chain, which the kernel refuses itself, is left to the launcher's
 * refusal line. {@code #!/usr/bin/env x} ends at {@code env}, a binary: what {@code env} then finds
 * on {@code PATH} is the command's own result. A file the factory cannot read is not judged further.
 *
 * <p>Implements FR21 of fix-terminal-receipt-convergence (D19 as amended 2026-10-17, second).
 */
final class ExecutableLookup {

    /** How many interpreters of a {@code #!} chain are judged before the kernel's own limit. */
    static final int MAX_HOPS = 4;

    /** The head the kernel reads for a {@code #!} line ({@code BINPRM_BUF_SIZE}). */
    private static final int HEAD = 256;

    /** {@code #!}, blanks, then the interpreter up to a blank, a newline or a NUL, as the kernel reads it. */
    private static final Pattern SHEBANG = Pattern.compile("#![ \\t]*([^ \\t\\n\\x00]*)");

    private ExecutableLookup() {}

    /**
     * @param name the command's first word
     * @param directory the child's working directory, or {@code null} for the JVM's
     * @param path the child's {@code PATH}, or {@code null} when nothing names one
     * @return the absolute executable the name resolves to
     * @throws IOException naming {@code name} when nothing runnable answers to it, or a hop of its
     *     interpreter chain does not resolve
     */
    static Path resolve(String name, @Nullable File directory, @Nullable String path) throws IOException {
        Path executable;
        try {
            executable = name.indexOf('/') >= 0
                    ? atPath(name, name, directory, "no executable file at ")
                    : onPath(name, path);
            Path script = executable;
            for (int hop = 0; hop < MAX_HOPS; hop++) {
                String interpreter = interpreterOf(script);
                if (interpreter == null) {
                    break;
                }
                script = atPath(name, interpreter, directory, "its #! interpreter is no executable file: ");
            }
        } catch (InvalidPathException e) {
            throw refused(name, "not a path the OS can open", e);
        }
        return executable;
    }

    private static Path atPath(String name, String file, @Nullable File directory, String missing) throws IOException {
        Path base = directory == null ? Path.of("") : directory.toPath();
        Path candidate = base.resolve(Path.of(file)).toAbsolutePath();
        if (!runnable(candidate)) {
            throw refused(name, missing + candidate, null);
        }
        return candidate;
    }

    /** The interpreter a {@code #!} head names — its first word — or {@code null} for none. */
    private static @Nullable String interpreterOf(Path script) {
        byte[] head;
        try (InputStream in = Files.newInputStream(script)) {
            head = in.readNBytes(HEAD);
        } catch (IOException unreadable) {
            return null;
        }
        Matcher line = SHEBANG.matcher(new String(head, StandardCharsets.ISO_8859_1));
        return line.lookingAt() && !line.group(1).isEmpty() ? line.group(1) : null;
    }

    private static Path onPath(String name, @Nullable String path) throws IOException {
        if (!name.isEmpty() && path != null) {
            for (String entry : path.split(":", -1)) {
                if (entry.startsWith("/")) {
                    Path candidate = Path.of(entry, name);
                    if (runnable(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        throw refused(name, "no executable file of that name on the child's PATH (" + path + ")", null);
    }

    private static boolean runnable(Path candidate) {
        return Files.isRegularFile(candidate) && Files.isExecutable(candidate);
    }

    private static IOException refused(String name, String why, @Nullable Exception cause) {
        return new IOException("Cannot run program \"" + name + "\": " + why, cause);
    }
}
