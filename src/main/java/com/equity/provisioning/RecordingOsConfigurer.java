package com.equity.provisioning;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** An OS that remembers which addresses it was told to hold. For tests and for off-box runs. */
public class RecordingOsConfigurer implements OsSecondaryIpConfigurer {

    private final Set<String> held = new LinkedHashSet<>();
    private final List<String> calls = new ArrayList<>();
    private RuntimeException failNext;

    public Set<String> held() { return Set.copyOf(held); }
    public List<String> calls() { return List.copyOf(calls); }
    public void failNextWith(RuntimeException e) { this.failNext = e; }

    @Override
    public synchronized void add(String privateIp, int prefixLength) {
        calls.add("add(" + privateIp + "/" + prefixLength + ")");
        maybeFail();
        held.add(privateIp);
    }

    @Override
    public synchronized void remove(String privateIp, int prefixLength) {
        calls.add("remove(" + privateIp + "/" + prefixLength + ")");
        maybeFail();
        held.remove(privateIp);
    }

    private void maybeFail() {
        if (failNext != null) {
            RuntimeException e = failNext;
            failNext = null;
            throw e;
        }
    }
}
