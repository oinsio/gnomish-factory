package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.HostExecHandle
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.jspecify.annotations.Nullable

/**
 * A daemon-free {@link TaskExecutionEnvironment} test double with container
 * semantics over local directories: materialize is a real {@code git clone
 * --no-hardlinks} from the factory clone, exec runs a local subprocess in the
 * box working copy, the file channel is plain files, and harvest is a real
 * fast-forward-only fetch from the box back into the factory clone — the same
 * git transport contract as the container adapter, minus Docker. Lets the
 * snapshot-first round protocol (FR21/FR22) and the boundary checks run as
 * fast local-bare-repo specs (task 5.5/5.6) with the identical git mechanics
 * the Docker-gated suite proves end-to-end.
 */
class LocalBoxEnvironment implements TaskExecutionEnvironment {

    private final GitProcessRunner runner = new GitProcessRunner()
    private final Path cloneDir
    private final Path boxRoot

    Path workingCopy
    private Path scratch
    private String branch

    LocalBoxEnvironment(Path cloneDir, Path boxRoot) {
        this.cloneDir = cloneDir
        this.boxRoot = boxRoot
    }

    @Override
    void materialize(String branch, @Nullable String commitPin) {
        this.branch = branch
        workingCopy = boxRoot.resolve('work')
        def clone = runner.run(
                boxRoot, 'clone', '--no-hardlinks', '--single-branch', '--branch', branch,
                cloneDir.toString(), workingCopy.toString())
        assert clone.exitCode() == 0: clone.stderr()
        runner.run(workingCopy, 'remote', 'remove', 'origin')
        runner.run(workingCopy, 'config', 'user.name', 'gnome')
        runner.run(workingCopy, 'config', 'user.email', 'gnome@sandbox.local')
        runner.run(workingCopy, 'config', 'gc.auto', '0')
        if (commitPin != null) {
            runner.run(workingCopy, 'reset', '--hard', commitPin)
        }
        scratch = Files.createDirectories(boxRoot.resolve('scratch'))
    }

    @Override
    ExecHandle exec(ExecCommand command) {
        def builder = new ProcessBuilder(command.command())
        builder.directory(workingCopy.toFile())
        builder.redirectErrorStream(command.mergeStderr())
        builder.environment().putAll(command.env())
        def process = builder.start()
        if (command.stdin() != null) {
            process.outputStream.withStream {
                it.write(command.stdin().getBytes(StandardCharsets.UTF_8))
            }
        } else {
            process.outputStream.close()
        }
        new HostExecHandle(process, Instant.now())
    }

    @Override
    void putFile(String path, byte[] content) {
        def resolved = workingCopy.resolve(path)
        Files.createDirectories(resolved.parent)
        Files.write(resolved, content)
    }

    @Override
    Optional<byte[]> readFile(String path, long sizeCap) {
        def resolved = workingCopy.resolve(path)
        Files.isRegularFile(resolved) ? Optional.of(Files.readAllBytes(resolved)) : Optional.<byte[]> empty()
    }

    @Override
    void harvest() {
        def fetch = runner.run(
                cloneDir, 'fetch', '--no-recurse-submodules', workingCopy.toString(), branch + ':' + branch)
        if (fetch.exitCode() != 0) {
            if (fetch.stderr().contains('non-fast-forward')) {
                throw new HarvestRefusedException(branch, fetch.stderr())
            }
            throw new HarvestFailedException(branch, fetch.stderr())
        }
    }

    @Override
    void dispose() {}

    @Override
    String scratchRoot() {
        scratch.toString()
    }

    @Override
    CapabilityPassport passport() {
        CapabilityPassport.container()
    }
}
