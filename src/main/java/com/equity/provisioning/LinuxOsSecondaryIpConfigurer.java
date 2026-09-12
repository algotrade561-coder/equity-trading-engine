package com.equity.provisioning;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs {@code ip addr add} so the kernel will let a socket bind to the new address.
 *
 * <p>Nothing here is clever, and that is the point. The address is validated as an IP literal
 * before it goes anywhere near a shell, the command is an argument array rather than a string, and
 * the only thing the process can run is {@code ip} under whatever privilege prefix is configured.
 * On the instance that prefix is {@code sudo}, restricted in {@code /etc/sudoers.d} to exactly
 * {@code /sbin/ip addr add} and {@code /sbin/ip addr del} for the service user — the runbook has
 * the line.</p>
 *
 * <p>Both operations are idempotent by inspection of the exit: adding an address that is already
 * there returns "RTNETLINK answers: File exists", removing one that is not there returns
 * "Cannot assign requested address". Both are the state the caller wanted, so both are success.</p>
 */
public class LinuxOsSecondaryIpConfigurer implements OsSecondaryIpConfigurer {

    private static final Logger log = LoggerFactory.getLogger(LinuxOsSecondaryIpConfigurer.class);

    private final String device;
    private final String privilegeCommand;

    public LinuxOsSecondaryIpConfigurer(ProvisioningProperties properties) {
        this.device = properties.getNetworkInterface();
        this.privilegeCommand = properties.getPrivilegeCommand();
    }

    @Override
    public void add(String privateIp, int prefixLength) {
        run("add", privateIp, prefixLength, "File exists");
    }

    @Override
    public void remove(String privateIp, int prefixLength) {
        run("del", privateIp, prefixLength, "Cannot assign requested address");
    }

    private void run(String verb, String privateIp, int prefixLength, String alreadyDoneMarker) {
        if (!isIpv4Literal(privateIp)) {
            throw new IllegalArgumentException("refusing to pass '" + privateIp + "' to the shell: not an IPv4 literal");
        }
        if (prefixLength < 8 || prefixLength > 32) {
            throw new IllegalArgumentException("implausible prefix length " + prefixLength);
        }
        List<String> command = new ArrayList<>();
        if (privilegeCommand != null && !privilegeCommand.isBlank()) command.add(privilegeCommand.trim());
        command.addAll(List.of("ip", "addr", verb, privateIp + "/" + prefixLength, "dev", device));

        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("'" + String.join(" ", command) + "' did not finish in 15s");
            }
            if (p.exitValue() == 0 || out.contains(alreadyDoneMarker)) {
                log.info("OS: ip addr {} {}/{} dev {} — {}", verb, privateIp, prefixLength, device,
                        p.exitValue() == 0 ? "done" : "already in that state");
                return;
            }
            throw new IllegalStateException("'" + String.join(" ", command) + "' exited "
                    + p.exitValue() + ": " + out);
        } catch (IOException e) {
            throw new IllegalStateException("could not run '" + String.join(" ", command) + "': " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running ip addr " + verb, e);
        }
    }

    static boolean isIpv4Literal(String value) {
        if (value == null) return false;
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) return false;
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }
}
