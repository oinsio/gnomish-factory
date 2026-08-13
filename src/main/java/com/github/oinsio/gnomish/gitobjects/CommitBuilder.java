package com.github.oinsio.gnomish.gitobjects;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The plumbing chain behind {@link GitObjects#commit}: verify the compare-and-swap precondition,
 * read the parent tree into a private temporary index, apply the tree edits, {@code write-tree} +
 * {@code commit-tree}, then advance the ref with git's atomic {@code update-ref} (design D19). The
 * temp index is the only disk artifact and is removed in a {@code finally} — no working copy, no
 * checkout, no hooks.
 *
 * <p>Implements FR25 of add-sandbox-core.
 */
final class CommitBuilder {

    private final GitExec exec;
    private final Path tempDir;

    CommitBuilder(GitExec exec, Path tempDir) {
        this.exec = exec;
        this.tempDir = tempDir;
    }

    ObjectId build(CommitRequest request, @Nullable ObjectId currentTip) {
        checkPrecondition(request, currentTip);
        Path index = allocateIndex();
        try {
            Map<String, String> indexEnv = Map.of("GIT_INDEX_FILE", index.toString());
            check(exec.run(List.of("read-tree", request.parent().hex()), null, indexEnv, -1), "read-tree");
            int oidLength = request.parent().hex().length();
            for (TreeEdit edit : request.edits()) {
                applyEdit(edit, indexEnv, oidLength);
            }
            String tree = output(exec.run(List.of("write-tree"), null, indexEnv, -1), "write-tree");
            String newCommit = commitTree(tree, request);
            advanceRef(request, newCommit);
            return ObjectId.of(newCommit);
        } finally {
            deleteQuietly(index);
        }
    }

    private static void checkPrecondition(CommitRequest request, @Nullable ObjectId currentTip) {
        if (request.expectedTip().isPresent()) {
            if (!request.expectedTip().get().equals(currentTip)) {
                throw new StaleTipException("ref " + request.ref() + " expected "
                        + request.expectedTip().get().hex() + " but is "
                        + (currentTip == null ? "absent" : currentTip.hex()));
            }
        } else if (currentTip != null) {
            throw new StaleTipException("ref " + request.ref() + " already exists at " + currentTip.hex());
        }
    }

    private void applyEdit(TreeEdit edit, Map<String, String> indexEnv, int oidLength) {
        switch (edit) {
            case TreeEdit.PutFile put -> {
                String blob = output(
                        exec.run(List.of("hash-object", "-w", "--stdin"), put.content(), indexEnv, -1), "hash-object");
                check(
                        exec.run(
                                List.of("update-index", "--add", "--cacheinfo", "100644", blob, put.path()),
                                null,
                                indexEnv,
                                -1),
                        "update-index");
            }
            case TreeEdit.DeletePath delete -> deletePath(delete.path(), indexEnv, oidLength);
        }
    }

    private void deletePath(String path, Map<String, String> indexEnv, int oidLength) {
        GitExec.Result listing = exec.run(List.of("ls-files", "--cached", "-z", "--", path), null, indexEnv, -1);
        check(listing, "ls-files");
        StringBuilder info = new StringBuilder();
        String zeroOid = "0".repeat(oidLength);
        for (String entry : listing.stdoutText().split("\u0000", -1)) {
            if (!entry.isEmpty()) {
                info.append("0 ").append(zeroOid).append('\t').append(entry).append('\n');
            }
        }
        if (info.isEmpty()) {
            return;
        }
        check(
                exec.run(
                        List.of("update-index", "--index-info"),
                        info.toString().getBytes(StandardCharsets.UTF_8),
                        indexEnv,
                        -1),
                "update-index --index-info");
    }

    private String commitTree(String tree, CommitRequest request) {
        CommitMetadata meta = request.metadata();
        Map<String, String> env = new HashMap<>();
        env.put("GIT_AUTHOR_NAME", meta.author().name());
        env.put("GIT_AUTHOR_EMAIL", meta.author().email());
        env.put("GIT_AUTHOR_DATE", CommitMetadata.gitDate(meta.authorTime()));
        env.put("GIT_COMMITTER_NAME", meta.committer().name());
        env.put("GIT_COMMITTER_EMAIL", meta.committer().email());
        env.put("GIT_COMMITTER_DATE", CommitMetadata.gitDate(meta.committerTime()));
        byte[] message = meta.message().getBytes(StandardCharsets.UTF_8);
        return output(
                exec.run(List.of("commit-tree", tree, "-p", request.parent().hex()), message, env, -1), "commit-tree");
    }

    private void advanceRef(CommitRequest request, String newCommit) {
        String oldValue = request.expectedTip().map(ObjectId::hex).orElse("");
        GitExec.Result result = exec.run(List.of("update-ref", request.ref(), newCommit, oldValue), null, Map.of(), -1);
        if (result.exitCode() != 0) {
            throw new StaleTipException("update-ref " + request.ref() + " rejected (tip moved concurrently): "
                    + result.stderr().strip());
        }
    }

    private Path allocateIndex() {
        try {
            Path index = Files.createTempFile(tempDir, "gnomish-index-", ".idx");
            Files.delete(index);
            return index;
        } catch (IOException e) {
            throw new GitObjectsException("could not allocate temp index in " + tempDir, e);
        }
    }

    private static void deleteQuietly(Path index) {
        try {
            Files.deleteIfExists(index);
        } catch (IOException ignored) {
            // Best effort: a leftover temp index in the factory-private dir is harmless.
        }
    }

    private static void check(GitExec.Result result, String what) {
        if (result.exitCode() != 0) {
            throw new GitObjectsException(
                    "git " + what + " failed: " + result.stderr().strip());
        }
    }

    private static String output(GitExec.Result result, String what) {
        check(result, what);
        return result.stdoutText().trim();
    }
}
