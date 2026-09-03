package com.github.oinsio.gnomish.sandbox.environment

import java.nio.file.Files
import java.nio.file.Path

/**
 * Kept in sync with {@link DockerCliSpec} and {@link DockerCliBoundedSpec}: both drive
 * {@link DockerCli} against a fake {@code docker} executable rather than a real daemon; this is
 * the one place that writes it, so the shell shebang and executable bit stay in one place.
 */
class FakeDockerBinary {

    private FakeDockerBinary() {
    }

    static String write(Path tempDir, String script) {
        Path bin = tempDir.resolve('fakedocker')
        Files.writeString(bin, "#!/bin/sh\n" + script)
        bin.toFile().setExecutable(true)
        bin.toString()
    }
}
