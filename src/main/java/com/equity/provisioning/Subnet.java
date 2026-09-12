package com.equity.provisioning;

import java.util.ArrayList;
import java.util.List;

/**
 * The arithmetic of an IPv4 CIDR block, done once and tested, because getting it wrong here means
 * asking AWS for an address it will refuse — or worse, one it will grant that belongs to the
 * network itself.
 *
 * <p>AWS reserves five addresses in every subnet: the network address, the router (+1), the DNS
 * server (+2), one for future use (+3), and the broadcast address (last). Handing any of those to
 * a user would be refused by AssignPrivateIpAddresses, but only after the row had been created, so
 * they are simply never offered.</p>
 */
final class Subnet {

    private Subnet() {}

    static int prefixOf(String cidr) {
        String[] parts = cidr.trim().split("/");
        if (parts.length != 2) throw new IllegalArgumentException("not a CIDR block: " + cidr);
        int prefix = Integer.parseInt(parts[1]);
        if (prefix < 16 || prefix > 28) {
            throw new IllegalArgumentException("subnet prefix /" + prefix + " is outside what AWS allows (/16 to /28)");
        }
        return prefix;
    }

    static boolean contains(String cidr, String ip) {
        try {
            String[] parts = cidr.trim().split("/");
            int prefix = Integer.parseInt(parts[1]);
            long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
            return (toLong(parts[0]) & mask) == (toLong(ip) & mask);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Every address a host may hold, in order, with the five AWS reserves left out. */
    static List<String> usableAddresses(String cidr) {
        String[] parts = cidr.trim().split("/");
        int prefix = prefixOf(cidr);
        long network = toLong(parts[0]) & ((0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL);
        long size = 1L << (32 - prefix);
        List<String> out = new ArrayList<>();
        // +0 network, +1 router, +2 DNS, +3 reserved, last broadcast.
        for (long offset = 4; offset < size - 1; offset++) {
            out.add(toDotted(network + offset));
        }
        return out;
    }

    private static long toLong(String dotted) {
        String[] o = dotted.trim().split("\\.");
        if (o.length != 4) throw new IllegalArgumentException("not an IPv4 address: " + dotted);
        long v = 0;
        for (String part : o) {
            int b = Integer.parseInt(part);
            if (b < 0 || b > 255) throw new IllegalArgumentException("not an IPv4 address: " + dotted);
            v = (v << 8) | b;
        }
        return v;
    }

    private static String toDotted(long v) {
        return ((v >> 24) & 0xFF) + "." + ((v >> 16) & 0xFF) + "." + ((v >> 8) & 0xFF) + "." + (v & 0xFF);
    }
}
