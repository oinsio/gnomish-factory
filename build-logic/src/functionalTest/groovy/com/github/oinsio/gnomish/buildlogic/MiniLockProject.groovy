package com.github.oinsio.gnomish.buildlogic

import org.gradle.testkit.runner.BuildResult

import java.nio.file.Path

/**
 * The miniature build the dependency regeneration command is exercised on (FR3, NFR-R1 of
 * add-dependency-verification).
 *
 * It mirrors the real build where the command depends on it: every configuration locked
 * ({@code java-conventions}' {@code lockAllConfigurations()}), verification metadata seeded with the
 * real file's configuration block (sha256 checksums, metadata verified, only {@code sources} and
 * {@code javadoc} classifiers trusted), and {@code check} resolving the locked graph.
 *
 * Hermetic (NFR-R1): the modules are hand-written POMs with a byte-stable jar each, in a file-based
 * Maven repository inside the fixture, and every run is {@code --offline}.
 */
final class MiniLockProject {

    /** The documented regeneration command (developer guide, "Dependency locking and verification"). */
    static final List<String> REGENERATE = ['check', '--write-locks', '--write-verification-metadata', 'sha256']

    private final Path projectDir

    MiniLockProject(Path projectDir) {
        this.projectDir = projectDir
    }

    /** Writes the repository with {@code alpha} 1.0 and 1.1 and {@code beta} 1.0, and a build on alpha 1.0. */
    void write() {
        ['1.0', '1.1'].each { publish('alpha', it) }
        publish('beta', '1.0')
        GradleRunnerSupport.writeFile(projectDir, 'settings.gradle', "rootProject.name = 'mini-lock'\n")
        GradleRunnerSupport.writeFile(projectDir, 'gradle/verification-metadata.xml', '''\
<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="https://schema.gradle.org/dependency-verification https://schema.gradle.org/dependency-verification/dependency-verification-1.4.xsd">
   <configuration>
      <verify-metadata>true</verify-metadata>
      <verify-signatures>false</verify-signatures>
      <trusted-artifacts>
         <trust file=".*-javadoc\\.jar" regex="true"/>
         <trust file=".*-sources\\.jar" regex="true"/>
      </trusted-artifacts>
   </configuration>
   <components/>
</verification-metadata>
''')
        useAlpha('1.0')
    }

    /** Rewrites the build script so the locked graph is {@code alpha:version} plus {@code beta:1.0}. */
    void useAlpha(String version) {
        GradleRunnerSupport.writeFile(projectDir, 'build.gradle', """\
plugins {
    id 'base'
}

repositories {
    maven { url = file('repo') }
}

dependencyLocking {
    lockAllConfigurations()
}

configurations {
    libs
}

dependencies {
    libs 'com.example:alpha:${version}'
    libs 'com.example:beta:1.0'
}

def resolveLibs = tasks.register('resolveLibs') {
    def libs = configurations.libs
    inputs.files(libs)
    doLast { libs.files.size() }
}

tasks.named('check') {
    dependsOn resolveLibs
}
""")
    }

    /** The lockfile and the verification metadata, as their lines. */
    Map<String, List<String>> lockState() {
        [
            lockfile: projectDir.resolve('gradle.lockfile').readLines(),
            metadata: projectDir.resolve('gradle/verification-metadata.xml').readLines()
        ]
    }

    BuildResult regenerate() {
        GradleRunnerSupport.runner(projectDir, *REGENERATE).build()
    }

    private void publish(String module, String version) {
        String base = "repo/com/example/${module}/${version}/${module}-${version}"
        GradleRunnerSupport.writeFile(projectDir, "${base}.pom", """\
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>${module}</artifactId>
  <version>${version}</version>
</project>
""")
        GradleRunnerSupport.writeFile(projectDir, "${base}.jar", "${module} ${version}\n")
    }
}
