package com.equity.provisioning;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This instance's place in the network: which instance, which interface, which subnet, which region.
 *
 * <p>Read from IMDSv2 — the token-first metadata service, because the token-less v1 is the one
 * every SSRF write-up uses and AWS lets it be disabled per instance. Any value pinned in
 * {@link ProvisioningProperties} wins, which is how the same code runs on a laptop; if all of them
 * are pinned the metadata service is never contacted.</p>
 *
 * <p>Failures are not fatal here. The value stays null and {@link IpAllocationService} refuses to
 * provision with a message naming the missing one — a clear "set this" at the moment it is needed,
 * rather than a startup crash on every machine that is not an EC2 instance.</p>
 */
public class InstanceMetadata {

    private static final Logger log = LoggerFactory.getLogger(InstanceMetadata.class);
    private static final String IMDS = "http://169.254.169.254";
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final ProvisioningProperties pinned;
    private final HttpClient http;
    private volatile boolean fetched;
    private volatile String instanceId;
    private volatile String eniId;
    private volatile String subnetCidr;
    private volatile String region;
    private volatile String primaryPrivateIp;

    public InstanceMetadata(ProvisioningProperties pinned) {
        this(pinned, HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    InstanceMetadata(ProvisioningProperties pinned, HttpClient http) {
        this.pinned = pinned;
        this.http = http;
    }

    public String instanceId()       { return first(pinned.getInstanceId(), () -> instanceId); }
    public String eniId()            { return first(pinned.getEniId(), () -> eniId); }
    public String subnetCidr()       { return first(pinned.getSubnetCidr(), () -> subnetCidr); }
    public String region()           { return first(pinned.getRegion(), () -> region); }
    public String primaryPrivateIp() { return first(pinned.getPrimaryPrivateIp(), () -> primaryPrivateIp); }

    private String first(String configured, Supplier<String> discovered) {
        if (present(configured)) return configured.trim();
        ensureFetched();
        return discovered.get();
    }

    private synchronized void ensureFetched() {
        if (fetched) return;
        fetched = true;
        if (present(pinned.getInstanceId()) && present(pinned.getEniId())
                && present(pinned.getSubnetCidr()) && present(pinned.getRegion())
                && present(pinned.getPrimaryPrivateIp())) {
            return;      // everything pinned: do not touch the network
        }
        try {
            String token = token();
            instanceId = get("/latest/meta-data/instance-id", token);
            region = get("/latest/meta-data/placement/region", token);
            primaryPrivateIp = get("/latest/meta-data/local-ipv4", token);
            String mac = get("/latest/meta-data/mac", token);
            if (present(mac)) {
                String base = "/latest/meta-data/network/interfaces/macs/" + mac.trim() + "/";
                eniId = get(base + "interface-id", token);
                subnetCidr = get(base + "subnet-ipv4-cidr-block", token);
            }
            log.info("instance metadata: instance={} eni={} subnet={} region={} primary={}",
                    instanceId, eniId, subnetCidr, region, primaryPrivateIp);
        } catch (Exception e) {
            log.warn("instance metadata unavailable ({}). Expected off AWS; on AWS check that "
                    + "IMDSv2 is reachable, or pin equity.provisioning.* in configuration.",
                    e.getMessage());
        }
    }

    private String token() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(IMDS + "/latest/api/token"))
                .timeout(TIMEOUT)
                .header("X-aws-ec2-metadata-token-ttl-seconds", "21600")
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
        return r.statusCode() == 200 ? r.body().trim() : null;
    }

    private String get(String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(IMDS + path)).timeout(TIMEOUT).GET();
        if (present(token)) b.header("X-aws-ec2-metadata-token", token);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode() == 200 ? r.body().trim() : null;
    }

    private static boolean present(String s) { return s != null && !s.isBlank(); }
}
