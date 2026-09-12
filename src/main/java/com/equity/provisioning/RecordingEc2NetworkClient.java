package com.equity.provisioning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An EC2 that does nothing but remember what it was asked.
 *
 * <p>Wired whenever provisioning is off, which is every machine that is not the production
 * instance. It lets the full sequence — assign, configure, allocate, associate, bind — be driven
 * end to end in a test and asserted step by step, and it lets the admin screen be exercised on a
 * laptop without an AWS account. Deterministic identifiers, so a test can name what it expects.</p>
 *
 * <p>It also models the two failures that matter: an address already on the interface, and an
 * exhausted quota. The compensation path in {@link IpAllocationService} exists for exactly those,
 * and a mock that never fails would leave it untested.</p>
 */
public class RecordingEc2NetworkClient implements Ec2NetworkClient {

    private final AtomicInteger eipSequence = new AtomicInteger();
    private final AtomicInteger associationSequence = new AtomicInteger();
    private final Map<String, List<String>> privateIpsByEni = new LinkedHashMap<>();
    private final Map<String, EipInfo> eips = new LinkedHashMap<>();
    private final List<String> calls = new ArrayList<>();
    private int quota = Integer.MAX_VALUE;
    private RuntimeException failNext;

    /** Every call in order, as {@code name(args)}. What a test asserts against. */
    public List<String> calls() { return List.copyOf(calls); }

    public void limitQuotaTo(int max) { this.quota = max; }

    /** The next SDK call throws this, then the fault clears. */
    public void failNextWith(RuntimeException e) { this.failNext = e; }

    private void record(String call) {
        calls.add(call);
        if (failNext != null) {
            RuntimeException e = failNext;
            failNext = null;
            throw e;
        }
    }

    @Override
    public synchronized void assignPrivateIp(String eniId, String privateIp) {
        record("assignPrivateIp(" + eniId + "," + privateIp + ")");
        List<String> ips = privateIpsByEni.computeIfAbsent(eniId, k -> new ArrayList<>());
        if (ips.contains(privateIp)) {
            throw new IllegalStateException("InvalidParameterValue: " + privateIp + " is already assigned to " + eniId);
        }
        ips.add(privateIp);
    }

    @Override
    public synchronized void unassignPrivateIp(String eniId, String privateIp) {
        record("unassignPrivateIp(" + eniId + "," + privateIp + ")");
        privateIpsByEni.getOrDefault(eniId, new ArrayList<>()).remove(privateIp);
    }

    @Override
    public synchronized AllocatedEip allocateAddress() {
        record("allocateAddress()");
        if (eips.size() >= quota) {
            throw new IllegalStateException("AddressLimitExceeded: the maximum number of addresses has been reached");
        }
        int n = eipSequence.incrementAndGet();
        AllocatedEip eip = new AllocatedEip("eipalloc-mock" + n, "203.0.113." + n);
        eips.put(eip.allocationId(), new EipInfo(eip.allocationId(), eip.publicIp(), null, null, null, null, false));
        return eip;
    }

    @Override
    public synchronized String associateAddress(String allocationId, String eniId, String privateIp) {
        record("associateAddress(" + allocationId + "," + eniId + "," + privateIp + ")");
        EipInfo e = eips.get(allocationId);
        if (e == null) throw new IllegalStateException("InvalidAllocationID.NotFound: " + allocationId);
        String assoc = "eipassoc-mock" + associationSequence.incrementAndGet();
        eips.put(allocationId, new EipInfo(allocationId, e.publicIp(), assoc, privateIp, eniId, "i-mock", false));
        return assoc;
    }

    @Override
    public synchronized void disassociateAddress(String associationId) {
        record("disassociateAddress(" + associationId + ")");
        eips.replaceAll((id, e) -> associationId.equals(e.associationId())
                ? new EipInfo(id, e.publicIp(), null, null, null, null, false) : e);
    }

    @Override
    public synchronized void releaseAddress(String allocationId) {
        record("releaseAddress(" + allocationId + ")");
        eips.remove(allocationId);
    }

    @Override
    public synchronized List<String> describePrivateIps(String eniId) {
        return List.copyOf(privateIpsByEni.getOrDefault(eniId, List.of()));
    }

    @Override
    public synchronized List<EipInfo> describeAddresses() {
        return List.copyOf(eips.values());
    }

    /** Seeds the interface with addresses that were there before the automation — a manual setup. */
    public synchronized void preExisting(String eniId, String... privateIps) {
        privateIpsByEni.computeIfAbsent(eniId, k -> new ArrayList<>()).addAll(List.of(privateIps));
    }
}
