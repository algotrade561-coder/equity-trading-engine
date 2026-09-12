package com.equity.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.equity.domain.user.UserId;
import com.equity.platform.time.FixedTradingClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The provisioning sequence, driven end to end against a recording EC2 and OS.
 *
 * <p>Every operation the real client makes either costs money or changes the network the live
 * engine trades through, so none of this can be tried against the account. The recording client
 * remembers what it was asked, in order, and fails on command — which is how the compensation
 * path gets tested at all. A provisioner whose undo has never run is one whose first failure in
 * production leaves an Elastic IP on the bill that nobody is bound to.</p>
 */
class IpAllocationServiceTest {

    private static final UserId USER = UserId.of("07926329-989e-4d3c-b229-119b4a6c81dc");
    private static final UserId OTHER = UserId.of("11111111-1111-1111-1111-111111111111");
    private static final String ENI = "eni-0abc";

    private ProvisioningProperties props;
    private RecordingEc2NetworkClient ec2;
    private RecordingOsConfigurer os;
    private IpAllocationStore store;
    private List<String> bindings;
    private IpAllocationService service;

    @BeforeEach
    void wire() {
        props = new ProvisioningProperties();
        props.setEnabled(true);
        props.setEniId(ENI);
        props.setInstanceId("i-0test");
        props.setSubnetCidr("10.0.1.0/24");
        props.setRegion("ap-south-1");
        props.setPrimaryPrivateIp("10.0.1.10");
        props.setElasticIpQuota(5);

        ec2 = new RecordingEc2NetworkClient();
        ec2.preExisting(ENI, "10.0.1.10");          // the instance's own address
        os = new RecordingOsConfigurer();
        store = IpAllocationStore.inMemory();
        bindings = new ArrayList<>();
        service = new IpAllocationService(props, new InstanceMetadata(props), ec2, os, store,
                new IpAllocationService.SourceIpBinder() {
                    @Override public void bind(UserId u, String ip, String by) { bindings.add("bind " + u + " " + ip); }
                    @Override public void unbind(UserId u, String by) { bindings.add("unbind " + u); }
                },
                new FixedTradingClock(Instant.parse("2026-09-12T04:00:00Z")));
    }

    // ── The happy path ───────────────────────────────────────────────────────

    @Test
    void provisioningRunsTheFiveStepsInOrderAndEndsActive() {
        IpAllocation result = service.provision(USER, "admin");

        assertThat(result.status()).isEqualTo(IpAllocationStatus.ACTIVE);
        assertThat(result.privateIp())
                .as("first usable address: .0 to .3 are AWS reserves, .10 is the instance's own")
                .isEqualTo("10.0.1.4");
        assertThat(result.publicIp()).isEqualTo("203.0.113.1");
        assertThat(result.eipAllocationId()).isEqualTo("eipalloc-mock1");
        assertThat(result.eipAssociationId()).isNotNull();
        assertThat(result.isAutomated()).isTrue();

        assertThat(ec2.calls()).containsExactly(
                "assignPrivateIp(eni-0abc,10.0.1.4)",
                "allocateAddress()",
                "associateAddress(eipalloc-mock1,eni-0abc,10.0.1.4)");
        assertThat(os.calls()).containsExactly("add(10.0.1.4/24)");
        assertThat(bindings)
                .as("the user is bound only after the address fully exists")
                .containsExactly("bind " + USER + " 10.0.1.4");
    }

    @Test
    void provisioningIsIdempotentForAUserWhoAlreadyHasAnAddress() {
        IpAllocation first = service.provision(USER, "admin");
        IpAllocation again = service.provision(USER, "admin");

        assertThat(again.privateIp()).isEqualTo(first.privateIp());
        assertThat(ec2.calls())
                .as("a second call must not allocate a second Elastic IP")
                .hasSize(3);
    }

    @Test
    void eachUserGetsTheNextFreeAddress() {
        service.provision(USER, "admin");
        IpAllocation second = service.provision(OTHER, "admin");

        assertThat(second.privateIp()).isEqualTo("10.0.1.5");
        assertThat(second.publicIp()).isEqualTo("203.0.113.2");
    }

    @Test
    void anAddressAddedByHandIsNeverOfferedToAnotherUser() {
        // Somebody ran `ip addr add` and assigned .4 in the console last month. No row knows.
        ec2.preExisting(ENI, "10.0.1.4", "10.0.1.5");

        IpAllocation result = service.provision(USER, "admin");

        assertThat(result.privateIp())
                .as("the interface's live list is the truth, not the rows")
                .isEqualTo("10.0.1.6");
    }

    @Test
    void theWhitelistFlagIsTheOperatorsAndStartsFalse() {
        IpAllocation provisioned = service.provision(USER, "admin");
        assertThat(provisioned.whitelistedWithBroker())
                .as("Kite has no API for this; only a human can say it is done")
                .isFalse();

        IpAllocation confirmed = service.markWhitelisted(USER, true, "admin");
        assertThat(confirmed.whitelistedWithBroker()).isTrue();
        assertThat(confirmed.privateIp()).isEqualTo(provisioned.privateIp());
    }

    // ── Compensation ─────────────────────────────────────────────────────────

    @Test
    void aFailureAfterTheElasticIpIsAllocatedReleasesIt() {
        // Associate fails — the most expensive place to fail, because the EIP already exists.
        ec2.failNextWith(null);                          // clear any default
        RecordingEc2NetworkClient failing = new RecordingEc2NetworkClient() {
            @Override public synchronized String associateAddress(String a, String e, String p) {
                super.associateAddress(a, e, p);          // record it
                throw new IllegalStateException("InvalidNetworkInterfaceID.NotFound");
            }
        };
        failing.preExisting(ENI, "10.0.1.10");
        service = new IpAllocationService(props, new InstanceMetadata(props), failing, os, store,
                noopBinder(), new FixedTradingClock(Instant.parse("2026-09-12T04:00:00Z")));

        assertThatThrownBy(() -> service.provision(USER, "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provisioning failed");

        assertThat(failing.calls()).containsExactly(
                "assignPrivateIp(eni-0abc,10.0.1.4)",
                "allocateAddress()",
                "associateAddress(eipalloc-mock1,eni-0abc,10.0.1.4)",
                // compensation, newest first
                "releaseAddress(eipalloc-mock1)",
                "unassignPrivateIp(eni-0abc,10.0.1.4)");
        assertThat(failing.describeAddresses())
                .as("no Elastic IP may survive a failed provision — that is a bill with no user")
                .isEmpty();
        assertThat(os.held()).as("the OS address is removed too").isEmpty();

        IpAllocation row = store.current(USER).orElseThrow();
        assertThat(row.status()).isEqualTo(IpAllocationStatus.FAILED);
        assertThat(row.lastError()).contains("InvalidNetworkInterfaceID.NotFound");
        assertThat(bindings).as("a failed provision never binds the user").isEmpty();
    }

    @Test
    void aFailureAtTheOsStepUnassignsThePrivateIpAndAllocatesNothing() {
        os.failNextWith(new IllegalStateException("ip: RTNETLINK answers: Operation not permitted"));

        assertThatThrownBy(() -> service.provision(USER, "admin")).isInstanceOf(IllegalStateException.class);

        assertThat(ec2.calls()).containsExactly(
                "assignPrivateIp(eni-0abc,10.0.1.4)",
                "unassignPrivateIp(eni-0abc,10.0.1.4)");
        assertThat(ec2.describeAddresses())
                .as("failing before allocateAddress must mean no Elastic IP was ever created")
                .isEmpty();
    }

    @Test
    void aFailedRowCanBeRetriedAndSucceeds() {
        os.failNextWith(new IllegalStateException("transient"));
        assertThatThrownBy(() -> service.provision(USER, "admin")).isInstanceOf(IllegalStateException.class);

        IpAllocation retried = service.provision(USER, "admin");

        assertThat(retried.status()).isEqualTo(IpAllocationStatus.ACTIVE);
        assertThat(retried.lastError()).as("a successful retry clears the old failure").isNull();
        assertThat(store.live()).as("retry reuses the row rather than making a second").hasSize(1);
    }

    @Test
    void quotaExhaustionFailsCleanlyWithNothingLeftBehind() {
        ec2.limitQuotaTo(1);
        service.provision(USER, "admin");

        assertThatThrownBy(() -> service.provision(OTHER, "admin"))
                .hasMessageContaining("AddressLimitExceeded");

        assertThat(ec2.describePrivateIps(ENI))
                .as("the second user's private IP is unassigned again")
                .containsExactly("10.0.1.10", "10.0.1.4");
        assertThat(ec2.describeAddresses()).hasSize(1);
    }

    // ── Release ──────────────────────────────────────────────────────────────

    @Test
    void releasingAnAutomatedAddressUnbindsThenTearsDownInReverse() {
        service.provision(USER, "admin");
        bindings.clear();
        int before = ec2.calls().size();

        IpAllocation released = service.release(USER, "admin");

        assertThat(released.status()).isEqualTo(IpAllocationStatus.RELEASED);
        assertThat(bindings).containsExactly("unbind " + USER);
        assertThat(ec2.calls().subList(before, ec2.calls().size())).containsExactly(
                "disassociateAddress(" + released.eipAssociationId() + ")",
                "releaseAddress(eipalloc-mock1)",
                "unassignPrivateIp(eni-0abc,10.0.1.4)");
        assertThat(ec2.describeAddresses()).isEmpty();
        assertThat(os.held()).isEmpty();
        assertThat(store.current(USER)).as("a released row is history, not current").isEmpty();
    }

    @Test
    void releasingAManualAddressUnbindsAndTouchesNothingInAws() {
        service.adoptManual(USER, "10.0.1.50", "13.200.1.1", "admin");
        int before = ec2.calls().size();

        IpAllocation released = service.release(USER, "admin");

        assertThat(released.status()).isEqualTo(IpAllocationStatus.RELEASED);
        assertThat(ec2.calls()).as("the address was not this code's to free").hasSize(before);
        assertThat(bindings).contains("unbind " + USER);
    }

    // ── Guards ───────────────────────────────────────────────────────────────

    @Test
    void provisioningIsRefusedWhenTheFlagIsOff() {
        props.setEnabled(false);

        assertThatThrownBy(() -> service.provision(USER, "admin"))
                .hasMessageContaining("provisioning is off");
        assertThat(ec2.calls()).isEmpty();
    }

    @Test
    void provisioningNamesTheMissingPlacementValue() {
        props.setEniId(null);

        assertThatThrownBy(() -> service.provision(USER, "admin"))
                .hasMessageContaining("network interface id");
    }

    @Test
    void adoptingAManualAddressCountsAgainstCapacityButIsNotAutomated() {
        IpAllocation adopted = service.adoptManual(USER, "10.0.1.50", "13.200.1.1", "admin");

        assertThat(adopted.isAutomated()).isFalse();
        assertThat(adopted.status()).isEqualTo(IpAllocationStatus.ACTIVE);
        assertThat(service.capacity().allocated()).isEqualTo(1);
        assertThat(service.capacity().remaining()).isEqualTo(4);
        assertThat(bindings).containsExactly("bind " + USER + " 10.0.1.50");
    }

    private static IpAllocationService.SourceIpBinder noopBinder() {
        return new IpAllocationService.SourceIpBinder() {
            @Override public void bind(UserId u, String ip, String by) {}
            @Override public void unbind(UserId u, String by) {}
        };
    }
}
