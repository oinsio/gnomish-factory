package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.app.workspace.fake.AttemptCommitWorkspaces
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.gitobjects.GitObjectsFixture
import com.github.oinsio.gnomish.gitobjects.ObjectId
import java.nio.file.Path
import java.time.Duration

/**
 * Shared scaffolding for the {@link PinCheckedExternalCheckClient} specs (FR16, D10, NFR-C1 of
 * add-sandbox-core): a real bare repository seeded with the pinned definition files, the
 * attempt-commit workspace shape and the guarded check manifest every pin scenario hands the
 * guard.
 *
 * <p>Test fixture; not production code, never PIT-mutated.
 */
trait PinCheckFixture implements GitObjectsFixture {

    GitObjects gitObjects
    ObjectId baseTip

    /** Seeds {@code refs/heads/base} with the pinned files and opens it for object reads. */
    void seedPinnedRepo(Path tempDir) {
        Path bare = seedBareRepo(tempDir, [
            '.github/workflows/ci.yml': "name: ci\n",
            'config/analyzer.yml': "rules: strict\n",
        ])
        gitObjects = openGitObjects(bare, tempDir)
        baseTip = gitObjects.resolveRef('refs/heads/base').orElseThrow()
    }

    /** A sandboxed-mode workspace whose harvested attempt commit is {@code attempt}. */
    Workspace workspaceAt(ObjectId attempt) {
        AttemptCommitWorkspaces.at(attempt.hex())
    }

    /** The guarded external check — the CI workflow — with {@code pinPaths} law-declared. */
    VerifyCheck.External check(List<String> pinPaths) {
        new VerifyCheck.External(
                '.github/workflows/ci.yml', 'github', Duration.ofSeconds(1), Duration.ofSeconds(5),
                VerifyCheck.TimeoutClass.QUALITY, pinPaths)
    }
}
