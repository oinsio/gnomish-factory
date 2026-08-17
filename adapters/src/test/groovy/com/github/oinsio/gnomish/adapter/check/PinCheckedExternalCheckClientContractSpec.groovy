package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.app.port.check.ExternalCheckPinContributor
import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.port.contract.ExternalCheckClientContract
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.gitobjects.GitObjectsFixture
import java.nio.file.Path
import java.time.Duration
import spock.lang.TempDir

/**
 * FR16, D10 of add-sandbox-core: the pin-check guard is itself an {@code ExternalCheckClient}
 * and, with an empty pin union (the vacuous pass — the interactive-client shape), is
 * contract-transparent: every port-level contract case holds through it unchanged, over a real
 * repository. The pin behaviors themselves (pinned-diff refusal, early substitution, fail-closed
 * degradations) are covered by {@code PinCheckedExternalCheckClientSpec}.
 */
class PinCheckedExternalCheckClientContractSpec extends ExternalCheckClientContract implements GitObjectsFixture {

    @TempDir
    Path tempDir

    @Override
    protected Optional<PollStatus> arrange(PollVariant variant) {
        GitObjects gitObjects = openGitObjects(seedBareRepo(tempDir, ['README.md': 'seed']), tempDir)
        def guarded = new PinCheckedExternalCheckClient(
                new ScriptedExternalCheckClient([scriptedStatus(variant)]),
                ExternalCheckPinContributor.none(),
                gitObjects,
                'refs/heads/base')
        def check = new VerifyCheck.External(
                'ci', 'github', Duration.ofSeconds(1), Duration.ofSeconds(10), VerifyCheck.TimeoutClass.QUALITY)
        Optional.of(guarded.poll(check, new FakeWorkspace()))
    }
}
