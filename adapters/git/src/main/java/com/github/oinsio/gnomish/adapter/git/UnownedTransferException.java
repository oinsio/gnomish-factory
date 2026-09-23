package com.github.oinsio.gnomish.adapter.git;

import java.io.Serial;

/**
 * Thrown by {@link GitProcessRunner}'s untyped entry when the argv names a transfer — {@code
 * fetch}, {@code clone}, {@code pull}, {@code submodule update}, {@code remote update} — that no
 * owner built: every factory transfer is a {@code GitTransfer} value from the {@code :gittransfer}
 * leaf, handed to the runner's typed entry, and a hand-built one is the escape hatch that entry
 * exists to close (FR8, design D5 of own-git-transfer-argv). Thrown before any process is
 * launched, so nothing reaches a remote.
 *
 * <p>A programming error, not an outcome: no caller catches it, and a spec that meets it has
 * spelled a transfer by hand.
 *
 * <p>Implements FR8 of own-git-transfer-argv.
 */
public final class UnownedTransferException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param subcommand the transfer subcommand the argv named, e.g. {@code fetch}
     */
    UnownedTransferException(String subcommand) {
        super("git " + subcommand + " is a transfer and may not be built by hand: build it as a GitTransfer"
                + " value (the one owner of every factory transfer) and hand it to the runner's typed entry");
    }
}
