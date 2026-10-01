package com.github.oinsio.gnomish.app.project;

import com.github.oinsio.gnomish.app.UsageException;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;

/**
 * An operator configuration file — the host file {@code factory.yaml} or a project's {@code
 * project.yaml} — parsed as YAML by Spring's {@link YamlPropertySourceLoader}, the loader the
 * configuration binds from, so every property keeps its origin (file and line) for the violation
 * report and {@code project show} (design D3, NFR-O1). The text is read by the caller through a
 * {@link Reader} and parsed from memory: a process reads each file once (NFR-P1), and a spec
 * counts the reads through the same seam.
 *
 * <p>Implements FR2, FR5, NFR-O1, NFR-P1 of add-project-registry.
 */
public final class OperatorFile {

    /** Reads a file's text: {@link #FILESYSTEM} in production. */
    @FunctionalInterface
    public interface Reader {
        /** The whole text of {@code file}. */
        String read(Path file) throws IOException;
    }

    /**
     * The production reader. Spelled as a lambda over a literal {@code Files.readString(} call, not
     * the method reference: {@code RawCaptureGateSpec} finds capture owners by that literal.
     */
    public static final Reader FILESYSTEM = file -> Files.readString(file);

    private OperatorFile() {}

    /**
     * The text of {@code file}, or the empty string when it does not exist.
     *
     * @throws UncheckedIOException if the file exists and cannot be read
     */
    public static String read(Reader reader, Path file) {
        if (!Files.exists(file)) {
            return "";
        }
        try {
            return reader.read(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The property sources of {@code text}, one per YAML document, each origin naming {@code file}
     * and the line.
     *
     * @param file the file the text came from
     * @param text the file's content
     * @throws UsageException naming {@code file} if the text is not YAML
     */
    public static List<PropertySource<?>> parse(Path file, String text) {
        try {
            return new YamlPropertySourceLoader().load(file.toString(), new FileText(file, text));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            throw new UsageException(file + " is not valid YAML: " + e.getMessage());
        }
    }

    /**
     * Text already read from a file, presented as that file: its description is the path, so an
     * origin reads {@code /home/op/.gnomish/factory.yaml:7}, and {@link #getFile()} names it.
     */
    @NullMarked
    private static final class FileText extends ByteArrayResource {

        private final Path file;

        FileText(Path file, String text) {
            super(text.getBytes(StandardCharsets.UTF_8), file.toString());
            this.file = file;
        }

        @Override
        public String getDescription() {
            return file.toString();
        }

        @Override
        public File getFile() {
            return file.toFile();
        }
    }
}
