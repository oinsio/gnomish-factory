package com.github.oinsio.gnomish.build

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * The miniature multi-project build the license gate is exercised on (FR8, M2 of
 * add-project-license).
 *
 * It mirrors the real build's shape where the gate depends on it: the root applies
 * {@code license-gate-conventions} unchanged, so the two distributed modules the
 * convention names ({@code :bootstrap}, {@code :gnomish-plugin-api}) exist here too,
 * and the allowlist is the repository's own {@code config/allowed-licenses.json},
 * copied in rather than rewritten — a rule the real file drops, or a canonical name
 * the plugin's bundle renames, turns a scenario red.
 *
 * Hermetic (NFR-R1): the third-party modules are hand-written POMs (plus a manifest-only jar
 * each) in a file-based Maven repository inside the fixture, and every run is
 * {@code --offline}.
 */
final class MiniLicenseProject {

    static final String GROUP = 'com.example'

    private final Path projectDir

    MiniLicenseProject(Path projectDir) {
        this.projectDir = projectDir
    }

    /** Writes settings, build scripts, the allowlist copy and the offline repository. */
    void write() {
        GradleRunnerSupport.writeFile(projectDir, 'settings.gradle', '''\
rootProject.name = 'mini-license'
include 'bootstrap', 'gnomish-plugin-api'
''')
        GradleRunnerSupport.writeFile(projectDir, 'build.gradle', '''\
plugins {
    id 'license-gate-conventions'
}

allprojects {
    group = 'org.fixture'
}

subprojects {
    apply plugin: 'java-library'
    repositories {
        maven { url = rootProject.file('repo') }
    }
}
''')
        // The second distributed module: present because the convention names it, with no
        // third-party dependency, so every verdict below is `:bootstrap`'s.
        GradleRunnerSupport.writeFile(projectDir, 'gnomish-plugin-api/build.gradle', '')
        GradleRunnerSupport.writeFile(projectDir, 'config/allowed-licenses.json', allowlist().text)
        publish('lgpl-only', license('GNU Lesser General Public License, Version 2.1',
                'https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html'))
        // Logback's own POM declares exactly these two entries for its EPL-or-LGPL choice.
        publish('dual-epl-lgpl',
                license('Eclipse Public License - v 2.0', 'https://www.eclipse.org/legal/epl-v20.html')
                        + license('GNU Lesser General Public License',
                        'https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html'))
        publish('apache-variant', license('The Apache Software License, Version 2.0', null))
    }

    /** Makes {@code :bootstrap}'s runtime classpath exactly {@code com.example:<artifact>:1.0}. */
    void bootstrapDependsOn(String artifact) {
        GradleRunnerSupport.writeFile(projectDir, 'bootstrap/build.gradle', """\
dependencies {
    implementation '${GROUP}:${artifact}:1.0'
}
""")
    }

    BuildResult checkLicense() {
        runner().build()
    }

    BuildResult checkLicenseAndFail() {
        runner().buildAndFail()
    }

    private GradleRunner runner() {
        // The invocation flags of the CI job (design D2): the plugin supports neither.
        GradleRunnerSupport.runner(projectDir, 'checkLicense', '--no-configuration-cache', '--no-parallel')
    }

    private static Path allowlist() {
        String path = System.getProperty('gnomish.allowedLicensesFile')
        assert path != null: 'functionalTest must pass -Dgnomish.allowedLicensesFile (see build-logic/build.gradle)'
        Path.of(path)
    }

    private static String license(String name, String url) {
        "<license><name>${name}</name>${url == null ? '' : "<url>${url}</url>"}</license>"
    }

    private void publish(String artifact, String licenses) {
        String dir = "repo/${GROUP.replace('.', '/')}/${artifact}/1.0"
        GradleRunnerSupport.writeFile(projectDir, "${dir}/${artifact}-1.0.pom", """\
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>${GROUP}</groupId>
  <artifactId>${artifact}</artifactId>
  <version>1.0</version>
  <licenses>${licenses}</licenses>
</project>
""")
        Path jar = projectDir.resolve("${dir}/${artifact}-1.0.jar")
        Files.newOutputStream(jar).withCloseable { new JarOutputStream(it, new Manifest()).close() }
    }
}
