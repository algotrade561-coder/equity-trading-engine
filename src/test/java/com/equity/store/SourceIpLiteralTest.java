package com.equity.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Only an address literal may be pinned — never a name.
 *
 * <p>A hostname is resolved at bind time, on the broker call path, by whatever the resolver says
 * that second. That is a silent indirection between "the address the operator registered with the
 * broker" and "the address an order actually leaves from", and it is precisely the kind of gap that
 * ends with a refused order and no obvious cause.</p>
 */
class SourceIpLiteralTest {

    @Test
    void acceptsDottedQuadsAndColonHex() {
        assertThat(UserProfileService.isIpLiteral("10.0.1.20")).isTrue();
        assertThat(UserProfileService.isIpLiteral("172.31.255.1")).isTrue();
        assertThat(UserProfileService.isIpLiteral("fe80::1")).isTrue();
        assertThat(UserProfileService.isIpLiteral("2406:da1a:0:1::5")).isTrue();
    }

    @Test
    void refusesNamesAndNearMisses() {
        assertThat(UserProfileService.isIpLiteral("kite.zerodha.com")).isFalse();
        assertThat(UserProfileService.isIpLiteral("10.0.1")).isFalse();
        assertThat(UserProfileService.isIpLiteral("10.0.1.256")).isFalse();
        assertThat(UserProfileService.isIpLiteral("10.0.1.20 ")).isFalse();
        assertThat(UserProfileService.isIpLiteral("ten.zero.one.twenty")).isFalse();
        assertThat(UserProfileService.isIpLiteral("")).isFalse();
    }
}
