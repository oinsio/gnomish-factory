package com.github.oinsio.gnomish.httpegress;

import java.net.InetAddress;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A CIDR block of the egress entry grammar ({@link EgressEntries}): a base address and a prefix
 * length, IPv4 ({@code 10.20.0.0/16}) or IPv6 ({@code fd00::/8}). An address of the other family
 * is never inside it. A part of the grammar's one owner, not a second grammar: only {@link
 * EgressEntries} reads it.
 *
 * <p>Implements FR17 of migrate-verify-to-executor-contract.
 */
final class Cidr {

    private static final char SEPARATOR = '/';

    /** An address, a slash and a prefix length of at most three digits. */
    private static final Pattern SHAPE = Pattern.compile("([^/]+)/(\\d{1,3})");

    private final byte[] base;
    private final int prefix;

    private Cidr(byte[] base, int prefix) {
        this.base = base;
        this.prefix = prefix;
    }

    /**
     * Whether {@code normalized} spells a CIDR block: an IP literal, a slash and a prefix length
     * within the family's width.
     */
    static boolean isCidr(String normalized) {
        Matcher matcher = SHAPE.matcher(normalized);
        if (!matcher.matches() || !EgressEntries.isAddressLiteral(matcher.group(1))) {
            return false;
        }
        int bits = InetAddress.ofLiteral(matcher.group(1)).getAddress().length * Byte.SIZE;
        return Integer.parseInt(matcher.group(2)) <= bits;
    }

    /**
     * The block {@code normalized} spells.
     *
     * @param normalized a string {@link #isCidr} accepts
     * @return the block
     */
    static Cidr parse(String normalized) {
        int slash = normalized.indexOf(SEPARATOR);
        return new Cidr(
                InetAddress.ofLiteral(normalized.substring(0, slash)).getAddress(),
                Integer.parseInt(normalized.substring(slash + 1)));
    }

    /**
     * Whether {@code address} lies inside the block: same family, and its first {@code prefix}
     * bits equal the base's.
     */
    boolean contains(InetAddress address) {
        byte[] candidate = address.getAddress();
        if (candidate.length != base.length) {
            return false;
        }
        for (int bit = 0; bit < prefix; bit++) {
            int index = bit / Byte.SIZE;
            int mask = 0x80 >>> (bit % Byte.SIZE);
            if ((candidate[index] & mask) != (base[index] & mask)) {
                return false;
            }
        }
        return true;
    }
}
