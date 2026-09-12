package com.equity.provisioning;

import java.util.List;

/**
 * The handful of EC2 calls that give a user an address of their own.
 *
 * <p>A seam rather than a direct SDK dependency, for two reasons that matter more here than
 * usual. First, this code has to be testable on a laptop, and the SDK does not run without an AWS
 * account behind it. Second — and this is the one that decides the shape — every operation below
 * either <b>costs money</b> or <b>changes the network the live engine is trading through</b>. A
 * mock that records what would have been done, and a real implementation that does it, lets the
 * whole provisioning sequence be driven end to end in a test before it is ever pointed at the
 * account.</p>
 *
 * <p>Every method is written to be safe to retry: the service persists after each step and may
 * call again after a crash. The SDK calls themselves are idempotent-enough — assigning a private
 * IP already on the interface is an error the caller can recognise, releasing an address twice
 * fails cleanly.</p>
 */
public interface Ec2NetworkClient {

    /** Attaches a specific secondary private IP to the network interface. */
    void assignPrivateIp(String eniId, String privateIp);

    /** Detaches a secondary private IP from the network interface. */
    void unassignPrivateIp(String eniId, String privateIp);

    /** Allocates a new Elastic IP in the VPC. This is the call that starts costing money. */
    AllocatedEip allocateAddress();

    /** Binds an Elastic IP to a private IP on the interface. Returns the association id. */
    String associateAddress(String allocationId, String eniId, String privateIp);

    void disassociateAddress(String associationId);

    /** Frees the Elastic IP. This is the call that stops it costing money. */
    void releaseAddress(String allocationId);

    /** The private IPs currently on the interface — the live truth, for collision checks. */
    List<String> describePrivateIps(String eniId);

    /**
     * Every Elastic IP in the region and what it is attached to.
     *
     * <p>Read-only, and the basis of the orphan check: an EIP that no allocation row claims is
     * either somebody's manual setup or a leak, and either way it is a line on the bill that
     * nobody is looking at.</p>
     */
    List<EipInfo> describeAddresses();

    record AllocatedEip(String allocationId, String publicIp) {}

    /**
     * One Elastic IP as AWS describes it. The association fields are null when it is not attached
     * to anything. {@code serviceManaged} marks addresses AWS itself owns — a load balancer's, a NAT
     * gateway's — which must never be touched however orphaned they look.
     */
    record EipInfo(String allocationId, String publicIp, String associationId,
                   String privateIp, String networkInterfaceId, String instanceId,
                   boolean serviceManaged) {}
}
