package com.github.oinsio.gnomish.architecture.seeded;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;

/**
 * A capture-family carrier in the shape rule (b) asks for, and the subject that proves the sink
 * scan's carrier-accessor derivation really finds an accessor (FR7, design D2 of
 * type-untrusted-text): {@code stderr} returns the carrier, {@code options} a list of it, and
 * {@code exitCode} is there so a derivation that simply returned every method name would be
 * caught.
 */
public record CarrierAccessorSeed(int exitCode, UntrustedText stderr, List<UntrustedText> options) {}
