package com.equity.provisioning;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Address;
import software.amazon.awssdk.services.ec2.model.AllocateAddressRequest;
import software.amazon.awssdk.services.ec2.model.AllocateAddressResponse;
import software.amazon.awssdk.services.ec2.model.AssignPrivateIpAddressesRequest;
import software.amazon.awssdk.services.ec2.model.AssociateAddressRequest;
import software.amazon.awssdk.services.ec2.model.AssociateAddressResponse;
import software.amazon.awssdk.services.ec2.model.DescribeAddressesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeNetworkInterfacesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeNetworkInterfacesResponse;
import software.amazon.awssdk.services.ec2.model.DisassociateAddressRequest;
import software.amazon.awssdk.services.ec2.model.DomainType;
import software.amazon.awssdk.services.ec2.model.NetworkInterfacePrivateIpAddress;
import software.amazon.awssdk.services.ec2.model.ReleaseAddressRequest;
import software.amazon.awssdk.services.ec2.model.UnassignPrivateIpAddressesRequest;

/**
 * The real thing. Wired only when {@code equity.provisioning.enabled} is true.
 *
 * <p>Credentials come from the SDK's default chain, which on an instance means the attached IAM
 * role through IMDSv2: temporary, rotated by AWS, never written anywhere. The role needs exactly
 * the eight actions used below and nothing else — the policy is in the runbook, and it is scoped
 * that tightly on purpose. A role that can allocate an address should not also be able to
 * terminate the instance.</p>
 *
 * <p>Every method logs what it did with the identifiers AWS returned, because those identifiers
 * are what it takes to undo it, and a log line is the fallback if the database write after the
 * call fails.</p>
 */
public class AwsEc2NetworkClient implements Ec2NetworkClient {

    private static final Logger log = LoggerFactory.getLogger(AwsEc2NetworkClient.class);

    private final Ec2Client ec2;

    public AwsEc2NetworkClient(InstanceMetadata metadata) {
        String region = metadata.region();
        this.ec2 = region != null && !region.isBlank()
                ? Ec2Client.builder().region(Region.of(region)).build()
                : Ec2Client.builder().build();
        log.info("EC2 client ready in region {} — Elastic IP provisioning is LIVE and will cost money",
                region == null ? "(default chain)" : region);
    }

    @Override
    public void assignPrivateIp(String eniId, String privateIp) {
        ec2.assignPrivateIpAddresses(AssignPrivateIpAddressesRequest.builder()
                .networkInterfaceId(eniId).privateIpAddresses(privateIp).build());
        log.info("EC2: assigned private IP {} to {}", privateIp, eniId);
    }

    @Override
    public void unassignPrivateIp(String eniId, String privateIp) {
        ec2.unassignPrivateIpAddresses(UnassignPrivateIpAddressesRequest.builder()
                .networkInterfaceId(eniId).privateIpAddresses(privateIp).build());
        log.info("EC2: unassigned private IP {} from {}", privateIp, eniId);
    }

    @Override
    public AllocatedEip allocateAddress() {
        AllocateAddressResponse r = ec2.allocateAddress(
                AllocateAddressRequest.builder().domain(DomainType.VPC).build());
        log.warn("EC2: allocated Elastic IP {} as {} — billing for it starts now", r.publicIp(), r.allocationId());
        return new AllocatedEip(r.allocationId(), r.publicIp());
    }

    @Override
    public String associateAddress(String allocationId, String eniId, String privateIp) {
        AssociateAddressResponse r = ec2.associateAddress(AssociateAddressRequest.builder()
                .allocationId(allocationId).networkInterfaceId(eniId).privateIpAddress(privateIp).build());
        log.info("EC2: associated {} with {} on {} as {}", allocationId, privateIp, eniId, r.associationId());
        return r.associationId();
    }

    @Override
    public void disassociateAddress(String associationId) {
        ec2.disassociateAddress(DisassociateAddressRequest.builder().associationId(associationId).build());
        log.info("EC2: disassociated {}", associationId);
    }

    @Override
    public void releaseAddress(String allocationId) {
        ec2.releaseAddress(ReleaseAddressRequest.builder().allocationId(allocationId).build());
        log.warn("EC2: released Elastic IP {} — billing for it stops", allocationId);
    }

    @Override
    public List<String> describePrivateIps(String eniId) {
        DescribeNetworkInterfacesResponse r = ec2.describeNetworkInterfaces(
                DescribeNetworkInterfacesRequest.builder().networkInterfaceIds(eniId).build());
        if (r.networkInterfaces().isEmpty()) return List.of();
        return r.networkInterfaces().get(0).privateIpAddresses().stream()
                .map(NetworkInterfacePrivateIpAddress::privateIpAddress)
                .toList();
    }

    @Override
    public List<EipInfo> describeAddresses() {
        return ec2.describeAddresses(DescribeAddressesRequest.builder().build()).addresses().stream()
                .map(a -> new EipInfo(a.allocationId(), a.publicIp(), a.associationId(),
                        a.privateIpAddress(), a.networkInterfaceId(), a.instanceId(), serviceManaged(a)))
                .toList();
    }

    /**
     * Whether AWS itself owns this address — a load balancer's, a NAT gateway's.
     *
     * <p>Those show up in DescribeAddresses with no instance and no allocation we made, which is
     * exactly what an orphan looks like. Releasing one takes down whatever it was fronting.</p>
     */
    private static boolean serviceManaged(Address a) {
        String owner = a.networkInterfaceOwnerId();
        return (owner != null && owner.startsWith("amazon"))
                || (a.associationId() != null && a.instanceId() == null && a.networkInterfaceId() != null
                    && a.privateIpAddress() != null && a.tags().stream()
                        .anyMatch(t -> t.key() != null && t.key().startsWith("aws:")));
    }
}
