/**
 * The {@code SecretsProvider} port (design D12): the single seam through which
 * the factory resolves named secrets — the GitHub tracker token today, gateway
 * master keys and depot credentials as later changes add them. Consumers ask
 * for a secret by name and are unaffected by the backing adapter, which
 * installation config selects; the port deliberately exposes no enumeration of
 * all secrets.
 *
 * <p>Resolution is fail-closed: a secret that cannot be resolved is an absent
 * value the consumer turns into a configuration error at startup or an
 * infrastructure failure at use time — never a silent empty string. Resolved
 * values are never logged and never enter a task environment.
 *
 * <p>The env/file implementation lives in {@code adapter.secrets}; Vault-class
 * (OpenBao) and OIDC adapters arrive with later changes without touching
 * consumers.
 *
 * <p>Implements FR18, NFR-S1 of add-sandbox-core.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.app.port.secrets;

import org.jspecify.annotations.NullMarked;
