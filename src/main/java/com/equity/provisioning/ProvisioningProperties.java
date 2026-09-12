package com.equity.provisioning;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where this machine sits in AWS, and whether the automation is allowed to act.
 *
 * <p>Everything here is non-secret. AWS credentials are never configured: the SDK takes temporary,
 * auto-rotating ones from the instance's IAM role via IMDSv2, so there is nothing to enter, store,
 * or rotate, and nothing for a leaked config file to give away.</p>
 *
 * <p>The placement values (instance, interface, subnet, region) are read from IMDS on the box and
 * only need setting here to run off-box. Set them all and IMDS is never contacted.</p>
 */
@ConfigurationProperties(prefix = "equity.provisioning")
public class ProvisioningProperties {

    /**
     * Master switch. Off means every provisioning call is refused and the mock EC2 client is wired,
     * so the code path can be exercised without an AWS account and without spending anything. It
     * is off by default because the first thing this does when on is allocate an Elastic IP, and
     * an Elastic IP costs money from the moment it exists.
     */
    private boolean enabled = false;

    /** Interface the secondary addresses are added to: {@code eth0} on Amazon Linux 2, {@code ens5} on AL2023. */
    private String networkInterface = "ens5";

    /** Prefix for the OS command. Empty runs {@code ip} directly (needs CAP_NET_ADMIN); otherwise {@code sudo}. */
    private String privilegeCommand = "sudo";

    /** The most Elastic IPs the account may hold in this region. The AWS default quota is 5. */
    private int elasticIpQuota = 5;

    private String instanceId;
    private String eniId;
    private String subnetCidr;
    private String region;
    /** The instance's own primary private address. Never a per-user allocation; never released. */
    private String primaryPrivateIp;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getNetworkInterface() { return networkInterface; }
    public void setNetworkInterface(String v) { this.networkInterface = v; }
    public String getPrivilegeCommand() { return privilegeCommand; }
    public void setPrivilegeCommand(String v) { this.privilegeCommand = v; }
    public int getElasticIpQuota() { return elasticIpQuota; }
    public void setElasticIpQuota(int v) { this.elasticIpQuota = v; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String v) { this.instanceId = v; }
    public String getEniId() { return eniId; }
    public void setEniId(String v) { this.eniId = v; }
    public String getSubnetCidr() { return subnetCidr; }
    public void setSubnetCidr(String v) { this.subnetCidr = v; }
    public String getRegion() { return region; }
    public void setRegion(String v) { this.region = v; }
    public String getPrimaryPrivateIp() { return primaryPrivateIp; }
    public void setPrimaryPrivateIp(String v) { this.primaryPrivateIp = v; }
}
