package com.equity.provisioning;

/**
 * Makes a secondary private IP bindable on this machine's interface.
 *
 * <p>AWS assigning an address to the ENI is necessary and not sufficient: the guest OS does not
 * know about it until something runs {@code ip addr add}. Until then a {@code bind()} to that
 * address fails with {@code EADDRNOTAVAIL}, and every one of the user's broker calls fails with
 * it. This is the step that most often gets forgotten when addresses are added by hand, and the
 * reason it is a first-class part of the sequence rather than a note in a runbook.</p>
 */
public interface OsSecondaryIpConfigurer {

    /** Adds the address to the interface with the subnet's prefix length. Idempotent. */
    void add(String privateIp, int prefixLength);

    /** Removes it. Idempotent — removing an address that is not there is not an error. */
    void remove(String privateIp, int prefixLength);
}
