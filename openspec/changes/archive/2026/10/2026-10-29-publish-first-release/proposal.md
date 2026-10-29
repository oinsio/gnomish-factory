# Publish the first release, v0.1.0

## Why

The release pipeline exists (`add-release-pipeline`, archived 2026-10-04): a tag push on a green
`main` builds the distribution archives, attests them and opens the release page. Its one manual
step — pushing the first tag and checking the page — was left unverified in that change and handed
to `fix-docker-exec-env-argv` as its task 6.3. A human step inside a code change blocks that
change's archive for as long as the maintainer waits for the right moment to tag, and the moment
depends on which changes are merged, not on that change's code. This change holds the step alone,
so every code change it waited on can archive on its own evidence.

Audience: the maintainer who pushes the tag. Urgency: the release notes
(`docs/releases/v0.1.0.md`) and the sandbox base image (`add-sandbox-base-image`, merged in #108)
are ready; the archive of `fix-docker-exec-env-argv` is the only thing waiting.

## What Changes

- **MODIFIED** nothing in code, specs or documentation: this change carries one human step, the
  first tag push, and its verification. `fix-docker-exec-env-argv` drops its task 6.3 and metric
  M6 in favour of this change.

## Goals

- **G1** `v0.1.0` is published from a green `main` through the existing release workflow.
- **G2** The release page is verified against the promises of `add-release-pipeline` (M1, M3 of
  that change) and the run link is recorded, so the pipeline's first-release scenario has evidence.

## Non-Goals

- **NG1** Any change to the release workflow, the archives, the attestations or the release notes.
  A defect the first run reveals is a finding against the release pipeline and gets its own change
  (`fix-release-...`); this change is not re-opened to fix it. Pickup condition: the first run
  that does not produce the four assets with their attestations.

## Users & Scenarios

- **U1** The maintainer merges the pending changes, pushes the tag on a green `main`, waits for the
  release workflow, and checks the page and the attestation from a clean machine.

## Requirements

- **FR1** The tag `v0.1.0` is pushed on a `main` commit whose CI is green and which contains
  `fix-docker-exec-env-argv` (the last change whose archive waited on the first release).
- **FR2** A maintainer who wants a rehearsal first pushes a throwaway `v0.0.1-rc.1` on a fork and
  deletes the tag and the release afterwards; nothing of the rehearsal reaches the origin
  repository.
- **NFR-R1** A failed first run is not retried by re-tagging the same commit with a different
  version: the tag is deleted together with the failed release draft, the defect is fixed in its
  own change, and `v0.1.0` is pushed again on the fixed `main`. One version, one tag, one commit.
- **NFR-O1** The task report carries the workflow run link and the `gh attestation verify`
  output.

## Operator Experience Criteria

- **UX1** A reader of the release page can follow `docs/releases/v0.1.0.md` from download to
  `gnomish --version` without consulting anything else.

## Success Metrics

- **M1** The release page holds exactly the four assets `add-release-pipeline` promised (the
  `tar.gz`, the `zip`, `SHA256SUMS`, the CycloneDX SBOM) and, per archive, a provenance and an SBOM
  attestation.
- **M2** `gh attestation verify gnomish-0.1.0.tar.gz --repo oinsio/gnomish-factory` passes.
- **M3** `gnomish --version` from the downloaded archive prints `0.1.0`.

## Open Questions

- **Q1** None. The step is decided; only its moment is the maintainer's.
