package com.equity.provisioning;

import com.equity.domain.user.UserId;
import com.equity.platform.time.TradingClock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gives a user an address of their own, and takes it back.
 *
 * <h2>The sequence, and why every step is persisted</h2>
 * <p>Provisioning is four AWS calls and one OS command, in an order that matters:</p>
 * <ol>
 *   <li>assign a free secondary private IP to the instance's network interface;</li>
 *   <li>add it to the OS so a socket can bind to it;</li>
 *   <li>allocate an Elastic IP — <b>this is where money starts</b>;</li>
 *   <li>associate the two;</li>
 *   <li>set it as the user's source address and rebind their broker clients.</li>
 * </ol>
 * <p>The row is saved after each step with the identifiers AWS returned. A crash between two steps
 * leaves a record that says exactly what exists, which is the only way to clean it up. A failure at
 * any step runs the completed steps backwards, so the account is never left holding an Elastic IP
 * that no user is bound to. That compensation is the most important code in the class, and it is
 * exercised by the tests against the recording client precisely because it cannot be exercised
 * safely against the real one.</p>
 *
 * <h2>What it will not touch</h2>
 * <p>Addresses that were set up by hand — the instance's own primary address, and any user whose
 * {@code source_ip} predates the automation — are adopted as rows with no allocation id. They count
 * against capacity and appear on the screen, and the automation will never release them. The
 * reference deployment learned this the way one does: the first version of a reconciler is always
 * one step from releasing something it did not create.</p>
 *
 * <h2>The step this class cannot do</h2>
 * <p>Registering the public IP against the user's API key in the Kite developer console. There is no
 * API for it. The row carries a {@code whitelistedWithBroker} flag that only an operator sets, and
 * the user's orders will be refused by the broker until it is true — so the screen shows a
 * provisioned-but-unwhitelisted user as not ready, not as done.</p>
 */
public class IpAllocationService {

    private static final Logger log = LoggerFactory.getLogger(IpAllocationService.class);

    /** Decides what the binding change does to live clients. Wired by the application layer. */
    public interface SourceIpBinder {
        /** Sets the user's source address in the broker config and rebinds their clients. */
        void bind(UserId userId, String privateIp, String actor);

        /** Clears it and rebinds to the default interface. */
        void unbind(UserId userId, String actor);
    }

    private final ProvisioningProperties properties;
    private final InstanceMetadata metadata;
    private final Ec2NetworkClient ec2;
    private final OsSecondaryIpConfigurer os;
    private final IpAllocationStore store;
    private final SourceIpBinder binder;
    private final TradingClock clock;

    public IpAllocationService(ProvisioningProperties properties, InstanceMetadata metadata,
                               Ec2NetworkClient ec2, OsSecondaryIpConfigurer os,
                               IpAllocationStore store, SourceIpBinder binder, TradingClock clock) {
        this.properties = properties;
        this.metadata = metadata;
        this.ec2 = ec2;
        this.os = os;
        this.store = store;
        this.binder = binder;
        this.clock = clock;
    }

    public boolean isEnabled() { return properties.isEnabled(); }

    public Optional<IpAllocation> current(UserId userId) { return store.current(userId); }

    public List<IpAllocation> all() { return store.live(); }

    // ── Provision ────────────────────────────────────────────────────────────

    /**
     * Gives the user an address. Idempotent: a user who already has a live one gets it back
     * unchanged, and a user whose last attempt FAILED is retried from the start.
     */
    public synchronized IpAllocation provision(UserId userId, String actor) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("provisioning is off (equity.provisioning.enabled=false); "
                    + "record the address by hand with adoptManual, or enable it on the AWS instance");
        }
        Optional<IpAllocation> existing = store.current(userId);
        if (existing.isPresent() && existing.get().isLive()) {
            return existing.get();
        }

        String eni = require(metadata.eniId(), "network interface id (equity.provisioning.eni-id, or IMDS on the instance)");
        String cidr = require(metadata.subnetCidr(), "subnet CIDR (equity.provisioning.subnet-cidr, or IMDS)");
        int prefix = Subnet.prefixOf(cidr);
        Instant now = clock.now();

        IpAllocation row = existing.orElseGet(() -> IpAllocation.begin(userId, now, actor))
                .withStatus(IpAllocationStatus.PENDING, now, actor)
                .withPlacement(eni, metadata.instanceId())
                .withError(null);
        row = store.save(row);

        // Reuse the previous private IP only if it is still in this subnet — a row carried across
        // a region migration holds an address AWS will refuse with "does not fall within the range".
        String privateIp = row.privateIp() != null && Subnet.contains(cidr, row.privateIp())
                ? row.privateIp()
                : pickFreePrivateIp(eni, cidr);

        try {
            ec2.assignPrivateIp(eni, privateIp);
            row = store.save(row.withPrivateIp(privateIp));

            os.add(privateIp, prefix);
            row = store.save(row.withStatus(IpAllocationStatus.OS_CONFIGURED, clock.now(), actor));

            Ec2NetworkClient.AllocatedEip eip = ec2.allocateAddress();
            row = store.save(row.withEip(eip.allocationId(), eip.publicIp()));

            String association = ec2.associateAddress(eip.allocationId(), eni, privateIp);
            row = store.save(row.withAssociation(association)
                    .withStatus(IpAllocationStatus.ASSOCIATED, clock.now(), actor));

            binder.bind(userId, privateIp, actor);
            row = store.save(row.withStatus(IpAllocationStatus.ACTIVE, clock.now(), actor));

            log.warn("PROVISIONED user={} private={} public={} — register {} in the Kite developer "
                    + "console for this user's API key, then mark it whitelisted. Until then the "
                    + "broker refuses their orders.", userId, privateIp, eip.publicIp(), eip.publicIp());
            return row;

        } catch (RuntimeException e) {
            log.error("provisioning FAILED for user={} at {}: {} — undoing what was done",
                    userId, row.status(), e.getMessage());
            compensate(row, prefix);
            IpAllocation failed = row.withStatus(IpAllocationStatus.FAILED, clock.now(), actor)
                    .withError(e.getMessage());
            store.save(failed);
            throw new IllegalStateException("provisioning failed for " + userId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Undoes a partial provision, most-recent step first, tolerating each undo failing.
     *
     * <p>Each step is attempted regardless of whether the previous undo succeeded, because they
     * are independent resources: an Elastic IP that could not be disassociated should still be
     * released if possible, and a private IP should still be unassigned. What cannot be undone is
     * logged with its identifier, which is what the operator needs to finish by hand.</p>
     */
    private void compensate(IpAllocation row, int prefix) {
        if (row.eipAssociationId() != null) {
            attempt("disassociate " + row.eipAssociationId(), () -> ec2.disassociateAddress(row.eipAssociationId()));
        }
        if (row.eipAllocationId() != null) {
            attempt("release " + row.eipAllocationId(), () -> ec2.releaseAddress(row.eipAllocationId()));
        }
        if (row.privateIp() != null) {
            attempt("remove " + row.privateIp() + " from the OS", () -> os.remove(row.privateIp(), prefix));
            attempt("unassign " + row.privateIp(), () -> ec2.unassignPrivateIp(row.eniId(), row.privateIp()));
        }
    }

    private static void attempt(String what, Runnable undo) {
        try {
            undo.run();
            log.info("compensation: {} — done", what);
        } catch (RuntimeException e) {
            log.error("compensation: could not {} ({}). This resource may still exist in AWS and "
                    + "needs a human.", what, e.getMessage());
        }
    }

    // ── Release ──────────────────────────────────────────────────────────────

    /**
     * Takes the user's address back.
     *
     * <p>For an address the automation created, this is the reverse of provisioning: unbind, then
     * disassociate, release, remove from the OS, unassign. For an address set up by hand it only
     * unbinds and marks the row released — the resources were never this code's to free.</p>
     */
    public synchronized IpAllocation release(UserId userId, String actor) {
        IpAllocation row = store.current(userId)
                .orElseThrow(() -> new IllegalStateException("no address allocated to " + userId));
        Instant now = clock.now();
        row = store.save(row.withStatus(IpAllocationStatus.RELEASING, now, actor));

        binder.unbind(userId, actor);

        if (row.isAutomated()) {
            if (!properties.isEnabled()) {
                throw new IllegalStateException("cannot release an automated allocation while "
                        + "provisioning is off — the AWS resources would be orphaned");
            }
            int prefix = Subnet.prefixOf(require(metadata.subnetCidr(), "subnet CIDR"));
            compensate(row, prefix);
        } else {
            log.info("released manual allocation for user={} — unbound only; the address {} was not "
                    + "created by this engine and is left in place", userId, row.privateIp());
        }
        return store.save(row.withStatus(IpAllocationStatus.RELEASED, clock.now(), actor));
    }

    // ── Manual adoption and the broker gate ──────────────────────────────────

    /**
     * Records an address that already exists — set up by hand, or on another provider entirely.
     *
     * <p>Binds the user to it and tracks it, and that is all. It is how the current single-user
     * deployment gets onto the screen without pretending the automation created its address.</p>
     */
    public synchronized IpAllocation adoptManual(UserId userId, String privateIp, String publicIp, String actor) {
        Optional<IpAllocation> existing = store.current(userId);
        if (existing.isPresent() && existing.get().isLive()) {
            throw new IllegalStateException(userId + " already has a live address: " + existing.get().privateIp());
        }
        binder.bind(userId, privateIp, actor);
        return store.save(IpAllocation.adoptedManual(userId, privateIp, publicIp, clock.now(), actor));
    }

    /** The operator confirming the public IP is registered against the key in Kite. */
    public synchronized IpAllocation markWhitelisted(UserId userId, boolean whitelisted, String actor) {
        IpAllocation row = store.current(userId)
                .orElseThrow(() -> new IllegalStateException("no address allocated to " + userId));
        return store.save(row.withWhitelisted(whitelisted, clock.now(), actor));
    }

    // ── Capacity ─────────────────────────────────────────────────────────────

    /**
     * How many more users can be given an address before something has to be raised.
     *
     * <p>Two limits apply and the smaller wins, and both are measured against what AWS actually
     * holds rather than against this table alone:</p>
     * <ul>
     *   <li>The Elastic IP quota is account-wide. The instance's own address counts against it,
     *       and so would anything allocated by hand, so the number in use comes from
     *       {@code DescribeAddresses}, not from the rows here.</li>
     *   <li>The interface holds a fixed number of addresses for the instance type, primary
     *       included. A t3.small holds four, so three users; the only way past it is a bigger
     *       instance or a second interface.</li>
     * </ul>
     *
     * <p>Getting this wrong is not cosmetic. Advertising five free slots on a box that has three
     * means the fourth click fails at AWS after the private address was already assigned — the
     * compensation unwinds it, but the administrator learns the limit from an error instead of
     * from the screen. If the describe call itself fails, the count falls back to the rows, which
     * can only over-estimate; the provisioning call still fails safely at the real limit.</p>
     */
    public Capacity capacity() {
        int live = store.live().size();
        int limit = properties.getInterfaceAddressLimit();

        int eipsInUse;
        try {
            eipsInUse = Math.max(live, ec2.describeAddresses().size());
        } catch (RuntimeException e) {
            log.warn("could not count Elastic IPs in use ({}); showing capacity from the rows alone", e.toString());
            eipsInUse = live;
        }
        int eipRemaining = Math.max(0, properties.getElasticIpQuota() - eipsInUse);

        // The primary occupies one slot on the interface; the rest are for users.
        int interfaceRemaining = Math.max(0, (limit - 1) - live);

        return new Capacity(live, properties.getElasticIpQuota(), Math.min(eipRemaining, interfaceRemaining),
                eipsInUse, limit - 1);
    }

    /**
     * @param allocated       addresses this table holds for users, automated or adopted
     * @param elasticIpQuota  the account's regional Elastic IP limit
     * @param remaining       users who can still be given an address — the smaller of the two limits
     * @param elasticIpsInUse Elastic IPs the account holds in this region, the instance's own included
     * @param interfaceSlots  user addresses the interface can hold (its limit less the primary)
     */
    public record Capacity(int allocated, int elasticIpQuota, int remaining,
                           int elasticIpsInUse, int interfaceSlots) {}

    // ── Choosing an address ──────────────────────────────────────────────────

    /**
     * A private IP in the subnet that nothing is using: not another row, not the interface's live
     * list, not the instance's own address, not the addresses AWS reserves.
     *
     * <p>Checked against the interface's live list as well as the rows, because the rows only know
     * what this code did and an address added by hand is exactly as taken.</p>
     */
    private String pickFreePrivateIp(String eni, String cidr) {
        Set<String> taken = new HashSet<>(ec2.describePrivateIps(eni));
        store.live().forEach(a -> { if (a.privateIp() != null) taken.add(a.privateIp()); });
        String primary = metadata.primaryPrivateIp();
        if (primary != null) taken.add(primary);

        for (String candidate : Subnet.usableAddresses(cidr)) {
            if (!taken.contains(candidate)) return candidate;
        }
        throw new IllegalStateException("no free private IP left in " + cidr + " — every usable address is assigned");
    }

    private static String require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("cannot provision without the " + what);
        }
        return value;
    }
}
