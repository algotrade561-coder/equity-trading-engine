package com.equity.provisioning;

import com.equity.domain.user.UserId;
import java.time.Instant;

/**
 * One user's address, and every AWS identifier needed to give it back.
 *
 * <h2>Why this is separate from the binding</h2>
 * <p>{@code broker_config.source_ip} is the <i>binding</i> — the one value the HTTP layer reads. This
 * is the <i>provenance</i>: which interface the private IP is on, which Elastic IP allocation it maps
 * to, which association ties them, and what state the sequence reached. Those identifiers are the
 * only way to release the resources later, and an Elastic IP whose allocation id has been lost is a
 * line on the bill that can only be found by hand.</p>
 *
 * <h2>The manual gate</h2>
 * <p>{@code whitelistedWithBroker} is the one field the automation cannot set. Kite has no API for
 * registering an IP against a key; an operator types the public IP into the developer console and
 * ticks the box. Until they do, the address exists and the binding is live but the broker refuses
 * the user's orders — so the UI shows it as the last step, not as done.</p>
 *
 * <p>Immutable, replaced by copy on each step, like {@code Position}. The provisioning sequence
 * persists after every transition so a crash leaves a row that says exactly what was done.</p>
 */
public record IpAllocation(
        Long id,
        UserId userId,
        String privateIp,
        String publicIp,
        String eniId,
        String instanceId,
        /** Null for an address somebody set up by hand. The automation never releases those. */
        String eipAllocationId,
        String eipAssociationId,
        IpAllocationStatus status,
        boolean whitelistedWithBroker,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        String updatedBy) {

    public static IpAllocation begin(UserId userId, Instant now, String by) {
        return new IpAllocation(null, userId, null, null, null, null, null, null,
                IpAllocationStatus.PENDING, false, null, now, now, by);
    }

    /** An address that already existed when the automation arrived. Adopted, tracked, never released. */
    public static IpAllocation adoptedManual(UserId userId, String privateIp, String publicIp,
                                             Instant now, String by) {
        return new IpAllocation(null, userId, privateIp, publicIp, null, null, null, null,
                IpAllocationStatus.ACTIVE, false, null, now, now, by);
    }

    /** Whether the automation created this and may therefore tear it down. */
    public boolean isAutomated() { return eipAllocationId != null; }

    public boolean isLive() { return status.isLive(); }

    public IpAllocation withStatus(IpAllocationStatus s, Instant now, String by) {
        return new IpAllocation(id, userId, privateIp, publicIp, eniId, instanceId, eipAllocationId,
                eipAssociationId, s, whitelistedWithBroker, lastError, createdAt, now, by);
    }

    public IpAllocation withPlacement(String eni, String instance) {
        return new IpAllocation(id, userId, privateIp, publicIp, eni, instance, eipAllocationId,
                eipAssociationId, status, whitelistedWithBroker, lastError, createdAt, updatedAt, updatedBy);
    }

    public IpAllocation withPrivateIp(String ip) {
        return new IpAllocation(id, userId, ip, publicIp, eniId, instanceId, eipAllocationId,
                eipAssociationId, status, whitelistedWithBroker, lastError, createdAt, updatedAt, updatedBy);
    }

    public IpAllocation withEip(String allocationId, String publicIp) {
        return new IpAllocation(id, userId, privateIp, publicIp, eniId, instanceId, allocationId,
                eipAssociationId, status, whitelistedWithBroker, lastError, createdAt, updatedAt, updatedBy);
    }

    public IpAllocation withAssociation(String associationId) {
        return new IpAllocation(id, userId, privateIp, publicIp, eniId, instanceId, eipAllocationId,
                associationId, status, whitelistedWithBroker, lastError, createdAt, updatedAt, updatedBy);
    }

    public IpAllocation withError(String error) {
        String trimmed = error == null ? null : error.substring(0, Math.min(1024, error.length()));
        return new IpAllocation(id, userId, privateIp, publicIp, eniId, instanceId, eipAllocationId,
                eipAssociationId, status, whitelistedWithBroker, trimmed, createdAt, updatedAt, updatedBy);
    }

    public IpAllocation withWhitelisted(boolean flag, Instant now, String by) {
        return new IpAllocation(id, userId, privateIp, publicIp, eniId, instanceId, eipAllocationId,
                eipAssociationId, status, flag, lastError, createdAt, now, by);
    }

    /** The store hands back the persisted identity after the first save. */
    public IpAllocation withId(Long persistedId) {
        return new IpAllocation(persistedId, userId, privateIp, publicIp, eniId, instanceId,
                eipAllocationId, eipAssociationId, status, whitelistedWithBroker, lastError,
                createdAt, updatedAt, updatedBy);
    }
}
