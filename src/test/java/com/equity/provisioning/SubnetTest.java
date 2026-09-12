package com.equity.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * CIDR arithmetic checked by hand, because a wrong answer here is an address AWS refuses — or one
 * it grants that belongs to the network itself.
 */
class SubnetTest {

    @Test
    void theFiveAwsReservesAreNeverOffered() {
        var usable = Subnet.usableAddresses("10.0.1.0/28");   // 16 addresses

        assertThat(usable)
                .as(".0 network, .1 router, .2 DNS, .3 reserved, .15 broadcast — eleven remain")
                .containsExactly("10.0.1.4", "10.0.1.5", "10.0.1.6", "10.0.1.7", "10.0.1.8",
                        "10.0.1.9", "10.0.1.10", "10.0.1.11", "10.0.1.12", "10.0.1.13", "10.0.1.14");
    }

    @Test
    void aSlash24HasTwoHundredAndFiftyOneUsableAddresses() {
        assertThat(Subnet.usableAddresses("172.31.16.0/24")).hasSize(251)
                .startsWith("172.31.16.4").endsWith("172.31.16.254");
    }

    @Test
    void theNetworkAddressIsDerivedEvenIfTheCidrNamesAHostInIt() {
        // AWS reports subnets as network/prefix, but a hand-typed value may not be normalised.
        assertThat(Subnet.usableAddresses("10.0.1.77/24")).startsWith("10.0.1.4");
    }

    @Test
    void containmentUsesTheMask() {
        assertThat(Subnet.contains("172.31.16.0/20", "172.31.31.254")).isTrue();
        assertThat(Subnet.contains("172.31.16.0/20", "172.31.32.1")).isFalse();
        assertThat(Subnet.contains("10.0.1.0/24", "10.0.2.1")).isFalse();
        assertThat(Subnet.contains("garbage", "10.0.0.1"))
                .as("an unparseable block contains nothing, so a stale address is re-picked")
                .isFalse();
    }

    @Test
    void prefixesOutsideWhatAwsAllowsAreRefused() {
        assertThat(Subnet.prefixOf("10.0.0.0/16")).isEqualTo(16);
        assertThat(Subnet.prefixOf("10.0.0.0/28")).isEqualTo(28);
        assertThatThrownBy(() -> Subnet.prefixOf("10.0.0.0/8")).hasMessageContaining("/8");
        assertThatThrownBy(() -> Subnet.prefixOf("10.0.0.0/30")).hasMessageContaining("/30");
        assertThatThrownBy(() -> Subnet.prefixOf("10.0.0.0")).hasMessageContaining("not a CIDR");
    }
}
