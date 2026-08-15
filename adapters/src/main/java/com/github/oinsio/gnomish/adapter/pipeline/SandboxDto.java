package com.github.oinsio.gnomish.adapter.pipeline;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code sandbox} block of a stage's {@code executor} (Mechanism) section —
 * the repo-side, tighten-only sandbox declarations (FR12, FR13, FR14 of
 * add-sandbox-core). Deserialized by {@link PipelineYaml}, then mapped to the
 * domain {@link com.github.oinsio.gnomish.domain.pipeline.Sandbox}.
 *
 * <p>Wire shape:
 *
 * <pre>{@code
 * executor:
 *   type: agent-cli
 *   model: ...
 *   sandbox:
 *     needs: [docker-inside]
 *     requiresFresh: true
 * }</pre>
 *
 * <p>Every field is nullable at the wire level (design D2): an absent
 * {@code needs} means no needs, an absent {@code requiresFresh} means the
 * segment-reuse default (FR13).
 *
 * <p><b>Tighten-only (FR14).</b> The {@code binding} field exists on the wire
 * only so a repo that tries to name a binding — {@code binding: host} (host
 * request) or {@code binding: container} (a named adapter) — is caught with a
 * clear "repo may only tighten" error by {@link StructuralValidation}, rather
 * than silently ignored. Adapter binding lives only in factory installation
 * config; the field is never carried into the domain model. Any other weakening
 * spelling a repo might attempt is rejected by Jackson's unknown-field capture
 * ({@link StructuralParse}), keeping the wire surface closed by default.
 *
 * <p>Implements FR12, FR13, FR14 of add-sandbox-core.
 *
 * @param needs the stage's named environment requirements, or {@code null} when
 *     the manifest declares none (FR12)
 * @param requiresFresh whether the stage forces a fresh environment, or
 *     {@code null} when omitted (defaults to segment reuse, FR13)
 * @param binding a repo-declared binding — always a tighten-only violation
 *     {@link StructuralValidation} reports (FR14); {@code null} when the
 *     manifest correctly declares none
 */
public record SandboxDto(
        @Nullable List<String> needs,
        @Nullable Boolean requiresFresh,
        @Nullable String binding) {}
