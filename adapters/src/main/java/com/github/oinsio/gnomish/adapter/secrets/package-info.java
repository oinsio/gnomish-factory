/**
 * The env/file adapter for the {@code SecretsProvider} port (design D12): the
 * zero-infrastructure default and, in this change, the sole implementation.
 *
 * <p>{@link com.github.oinsio.gnomish.adapter.secrets.EnvFileSecretsProvider}
 * resolves a named secret from the process environment, with a {@code
 * <name>_FILE} indirection that reads the value from a local file (the
 * Docker-secret convention) so a secret need not sit in a process's environment
 * table. Vault-class (OpenBao) and OIDC adapters arrive with later changes
 * behind the same port, invisible to consumers.
 *
 * <p>Implements FR18, NFR-S1 of add-sandbox-core.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.secrets;

import org.jspecify.annotations.NullMarked;
