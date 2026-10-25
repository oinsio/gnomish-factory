package com.github.oinsio.gnomish.testsupport

/**
 * The production files allowed to read text into the process from outside the trust boundary, each
 * with the family it mints or the reason no mint is owed (FR11, design D3 and D12 of
 * type-untrusted-text). This is the allowlist {@code RawCaptureGateSpec} scans against; it lives
 * beside the tree scanner rather than inside the spec because it is a registry — data a reviewer
 * reads and an author adds to — while the spec is the detector that judges it.
 *
 * <p>The three capture shapes the gate recognizes are a subprocess stream read, an HTTP response
 * body read, and a document file read. Every one of them produces a plain {@code String} that no
 * rule of the type gate can see, which is why the point of capture is guarded by name here even
 * though the sinks are guarded by type.
 *
 * <p>A file joining this map is the author stating which capture family of design D3 the text
 * belongs to. Two answers name no capture family at all: "mechanics", the deliberate carve-out for
 * {@code :subprocess} and {@code :gitobjects}, whose contract is process handling rather than text
 * policy; and "factory-authored", a document the factory itself wrote and reads back, which is
 * inside the boundary and owes no mint at the read — distinct from the {@code FACTORY} family,
 * which is where a sentence the factory composes for a carrier field is minted.
 */
class RawCaptureOwners {

    /**
     * Relative source path -> the family it mints, or why no mint is owed.
     *
     * <p>Built from a list of pairs, each value a single string literal, rather than a
     * {@code key: value} map literal with multi-line {@code +} concatenation. IntelliJ's
     * Groovy parser misreads both shapes — a long {@code key:} entry broken onto its own line,
     * and a string continued on the next line via a leading {@code +} — as a malformed
     * collection literal, even though greclipse (the project's Groovy formatter,
     * {@code gradle/spotless-greclipse.properties}) produces exactly this layout and the real
     * Groovy compiler accepts it. Two-element lists with one literal per value sidestep both
     * ambiguities without fighting the shared, non-configurable formatter.
     */
    static final Map<String, String> CAPTURE_OWNERS = [
        // --- subprocess streams: the mechanics layer and the readers built on it ---
        // CaptureRunner, GitExec, HostExecHandle and ContainerFileChannel left this map with
        // make-pipe-reads-non-parking: each now takes a child's output as a ChildOutput, the one
        // owner of a child's readable pipe (design D1), and reads no process stream itself.
        [
            'subprocess/src/main/java/com/github/oinsio/gnomish/subprocess/ChildOutput.java',
            'mechanics (D3 carve-out): the one owner of a child\'s readable pipe (design D1 of make-pipe-reads-non-parking); it hands bytes to a drain or a stream view and never turns them into text',
        ],
        [
            'subprocess/src/main/java/com/github/oinsio/gnomish/subprocess/ExecutableLookup.java',
            'mechanics (D3 carve-out): reads the first 256 bytes of a file to follow its #! chain before a launch (design D19 of fix-terminal-receipt-convergence); the bytes are parsed for an interpreter path and never leave the module as text',
        ],
        [
            'sandbox/docker/src/main/java/com/github/oinsio/gnomish/sandbox/environment/HostChannelFiles.java',
            'mechanics: reads a host channel file under a byte cap and answers bytes; the caller that turns a channel file into prose mints at its own edge, as its container-mode twin ContainerFileChannel does',
        ],
        // --- HTTP response bodies: the github bundle and the http check transport ---
        [
            'adapters/github/src/main/java/com/github/oinsio/gnomish/adapter/github/GithubConditionalRequestCache.java',
            'TRACKER (transport): holds the body as the cached/fresh envelope the tracker parsers read; the mint is at GithubTaskFetcher and GithubMarker, per D3',
        ],
        [
            'adapters/github/src/main/java/com/github/oinsio/gnomish/adapter/tracker/github/GithubClaimLease.java',
            'TRACKER (transport): body goes to the claim-comment parser, which yields ids and instants',
        ],
        [
            'adapters/github/src/main/java/com/github/oinsio/gnomish/adapter/tracker/github/GithubCommentThread.java',
            'TRACKER (transport): body goes to the comment parser feeding GithubMarker\'s mint',
        ],
        [
            'adapters/github/src/main/java/com/github/oinsio/gnomish/adapter/tracker/github/GithubCommentUpsert.java',
            'TRACKER (transport): body goes to GithubMarker.parse and to a json id read',
        ],
        [
            'adapters/github/src/main/java/com/github/oinsio/gnomish/adapter/tracker/github/GithubForeignRepoCheck.java',
            'TRACKER (transport): body is parsed into the repository full name and compared',
        ],
        [
            'adapters/github/src/main/java/com/github/oinsio/gnomish/adapter/tracker/github/GithubHeartbeat.java',
            'TRACKER (transport): body is parsed into the claim comment and its updated-at stamp',
        ],
        [
            'adapters/github/src/main/java/com/github/oinsio/gnomish/adapter/tracker/github/GithubIndexRepair.java',
            'TRACKER (transport): body is parsed into label names',
        ],
        [
            'adapters/github/src/main/java/com/github/oinsio/gnomish/adapter/tracker/github/GithubLabelProvisioner.java',
            'TRACKER (transport): body is parsed into label names',
        ],
        [
            'adapters/github/src/main/java/com/github/oinsio/gnomish/adapter/tracker/github/GithubStaleClaimRemoval.java',
            'TRACKER (transport): body is parsed into the claim comment',
        ],
        [
            'adapters/github/src/main/java/com/github/oinsio/gnomish/adapter/tracker/github/GithubStateWrites.java',
            'TRACKER (transport): body is parsed into the issue detail whose labels drive the write',
        ],
        [
            'adapters/src/main/java/com/github/oinsio/gnomish/adapter/check/http/JdkHttpCheckExchange.java',
            'MANIFEST/check (transport): streams the body under a byte cap into the check exchange; HttpExternalCheckClient matches it against the manifest\'s patterns and excerpts it through the carrier',
        ],
        // --- document file reads ---
        // The four host-side envelope readers that used to sit here — GitTaskStore,
        // GitTaskRepository, TerminalWriteMarker, StateFileWrite — left this map with
        // fix-envelope-medium: every one of them now reads through git — GitShowTip at the
        // registered commit, or RegisterBase for a write's own base (tighten-spec-contract) —
        // which captures a subprocess stream rather than a file and mints the BRANCH_DOCUMENT there. Their entries are removed rather than kept, because the
        // reached-every-file assertion is what makes this allowlist a gate.
        [
            'adapters/agent/src/main/java/com/github/oinsio/gnomish/adapter/agent/DecisionFileTransport.java',
            'AGENT: reads the agent\'s decision file and hands it to DecisionFileReader, which is the mint',
        ],
        [
            'adapters/agent/src/main/java/com/github/oinsio/gnomish/adapter/agent/RoundScratchDirectory.java',
            'AGENT: reads the tail of the agent CLI\'s stderr file and mints it at the read (design D18 of fix-terminal-receipt-convergence)',
        ],
        [
            'adapters/src/main/java/com/github/oinsio/gnomish/adapter/law/WorkingTreeLawSource.java',
            'MANIFEST: reads a law file into Read.Text, whose unreadable arm already mints; the law text itself is a Control artifact the pipeline feeds to an executor, not prose a sink renders',
        ],
        [
            'application/src/main/java/com/github/oinsio/gnomish/app/AdHocTaskSynthesizer.java',
            'TRACKER: reads an operator-supplied task file and mints its title and body one call later',
        ],
        [
            'adapters/src/main/java/com/github/oinsio/gnomish/adapter/secrets/EnvFileSecretsProvider.java',
            'no mint owed: a secret value, which never reaches a log line or a comment by construction (NFR-S1) — minting it would put it one accessor away from an exit',
        ],
        [
            'application/src/main/java/com/github/oinsio/gnomish/app/project/OperatorFile.java',
            'no mint owed: operator configuration — reads the host file or a project file the operator or `project add` wrote under the factory home; its values are parsed into typed paths and names (ProjectFile), checked against the level table, or bound as configuration — a violation line quotes a key or a value only through the console\'s human path',
        ],
        [
            'application/src/main/java/com/github/oinsio/gnomish/dashboard/SnapshotReader.java',
            'no mint owed: factory-authored — reads back the snapshot this factory wrote; the untrusted fields inside it are re-minted by SnapshotJsonReader',
        ],
        [
            'application/src/main/java/com/github/oinsio/gnomish/serveobservability/json/LedgerLineReader.java',
            'no mint owed: factory-authored — reads back the ledger this factory appended',
        ],
        // --- the src/main files outside the running factory that capture ---
        [
            'test-fixtures/src/main/groovy/com/github/oinsio/gnomish/gitobjects/LocalGitRepoFixture.groovy',
            'no mint owed: test-time only — drains a fixture git subprocess and answers its stdout to the calling spec, with the same text in the assertion message on a nonzero exit. It is not on any runtime path, and the scan sees it because RepoSourceTree walks every src/main in the build, :test-fixtures included',
        ],
        [
            'test-fixtures/src/main/groovy/com/github/oinsio/gnomish/adapter/git/LocalBoxEnvironment.groovy',
            'no mint owed: test-time only — a daemon-free box double whose file channel answers bytes, as its production twin ContainerFileChannel does, and whose exec hands the started process to HostExecHandle, whose ChildOutput CapturedExec drains and mints. It is not on any runtime path; it moved into :test-fixtures so :bootstrap\'s RoundTokenIdentitySpec can drive a whole round through it (task 7.7 of make-checkpoint-gate-durable)',
        ],
        [
            'test-fixtures/src/main/groovy/com/github/oinsio/gnomish/adapter/git/SeedTransferFixture.groovy',
            'mints subprocess: test-time only — drains a spec\'s own seeding transfer (a clone or a fetch that is test scaffolding, not a factory transfer, run outside the runner because the runner refuses every transfer the owner did not build — FR8 of own-git-transfer-argv) and hands both streams to GitCommandResult.of, the same mint and scrub the runner uses',
        ],
        [
            'test-fixtures/src/main/groovy/com/github/oinsio/gnomish/adapter/git/AdversarialLocalConfig.groovy',
            'no mint owed: test-time only — reads the argv a planted stand-in program recorded in its own log, to name it in the failure message of assertNothingRan (design D6 of own-git-invocation-policy). It is not on any runtime path, and the scan sees it because RepoSourceTree walks every src/main in the build, :test-fixtures included',
        ],
        [
            'test-fixtures/src/main/groovy/com/github/oinsio/gnomish/sandbox/environment/GuardImageAvailability.groovy',
            'no mint owed: test-time only — drains a docker prerequisite probe whose bytes are discarded, so a spec skips instead of failing offline',
        ],
        [
            'test-fixtures/src/main/groovy/com/github/oinsio/gnomish/sandbox/environment/TestDockerCommand.groovy',
            'no mint owed: test-time only — the one launch of the container fixtures (FR10 of add-sandbox-base-image): runs a docker command, or the contract build-arguments script SandboxBaseImage feeds into its docker build, with its merged output in a file read after the exit, and answers that text to the calling fixture or spec for assertions and build arguments. It is not on any runtime path, and the scan sees it because RepoSourceTree walks every src/main in the build, :test-fixtures included',
        ],
        [
            'test-fixtures/src/main/groovy/com/github/oinsio/gnomish/sandbox/environment/GroupLeader.groovy',
            'no mint owed: test-time only — reads the one readiness line a committed stand-in preset prints once it leads its process group, compares it with the library\'s own answer and discards it (design D19 of fix-terminal-receipt-convergence, the group sweep specs)',
        ],
        [
            'test-fixtures/src/main/groovy/com/github/oinsio/gnomish/testfixtures/sourcescan/SourceMarkerScan.groovy',
            'no mint owed: build-time only — reads this repository\'s own sources for the whole-tree gates. It is not on any runtime path, and the scan sees it because RepoSourceTree walks every src/main in the build, :test-fixtures included',
        ],
    ].collectEntries()

    private RawCaptureOwners() {}
}
