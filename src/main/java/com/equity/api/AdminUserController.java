package com.equity.api;

import com.equity.domain.user.UserId;
import com.equity.platform.security.CurrentUser;
import com.equity.provisioning.IpAllocation;
import com.equity.provisioning.IpAllocationService;
import com.equity.store.AppUserEntity;
import com.equity.store.AppUserRepository;
import com.equity.store.UserProfileService;
import com.equity.user.UserAccount;
import com.equity.user.UserRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Users, and the addresses that let them trade.
 *
 * <h2>What an administrator does here</h2>
 * <p>Adds a user by email — which is what puts them on the allow-list, since the allow-list <i>is</i>
 * the user table. Enables and disables them. Gives them an address, or records the one they were
 * given by hand. And ticks the one box the automation cannot: that the public IP has been registered
 * against the user's API key in the Kite developer console. Until that box is ticked the user is
 * shown as not ready, because the broker will refuse their orders however correct everything else
 * is.</p>
 *
 * <h2>Deleting</h2>
 * <p>Removes the account — login, settings, broker configuration. The user's trading records stay:
 * positions, ledger and audit rows are keyed by the trading id and are not touched, so history a
 * regulator can ask for survives the account that made it. Three things are refused: deleting
 * yourself, deleting a user who holds a position (close it first — the dashboard would lose sight of
 * it), and deleting a user whose address is still allocated (release it first — an Elastic IP bound
 * to nobody is a bill nobody is watching).</p>
 *
 * <p>Every endpoint requires ADMIN. Every change names who made it.</p>
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private static final Logger log = LoggerFactory.getLogger(AdminUserController.class);

    private final CurrentUser currentUser;
    private final AppUserRepository users;
    private final UserRegistry registry;
    private final UserProfileService profiles;
    private final IpAllocationService allocations;

    public AdminUserController(CurrentUser currentUser, AppUserRepository users, UserRegistry registry,
                               UserProfileService profiles, IpAllocationService allocations) {
        this.currentUser = currentUser;
        this.users = users;
        this.registry = registry;
        this.profiles = profiles;
        this.allocations = allocations;
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    @GetMapping
    public Map<String, Object> list() {
        currentUser.requireAdmin();
        List<Map<String, Object>> rows = users.findAll().stream()
                .sorted((a, b) -> a.getCreatedAt() == null || b.getCreatedAt() == null ? 0
                        : a.getCreatedAt().compareTo(b.getCreatedAt()))
                .map(this::describe)
                .toList();
        IpAllocationService.Capacity capacity = allocations.capacity();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("users", rows);
        m.put("provisioningEnabled", allocations.isEnabled());
        m.put("capacity", Map.of(
                "allocated", capacity.allocated(),
                "quota", capacity.elasticIpQuota(),
                "remaining", capacity.remaining(),
                "elasticIpsInUse", capacity.elasticIpsInUse(),
                "interfaceSlots", capacity.interfaceSlots()));
        return m;
    }

    // ── Users ────────────────────────────────────────────────────────────────

    public record CreateUserRequest(String email, String displayName, String role) {}

    /**
     * Adds a user. This is what admits them: sign-in checks the email against this table.
     *
     * <p>They arrive disabled for trading in every sense that matters — no broker credentials, no
     * address, entries disarmed — and become able to trade only as each of those is done. A new
     * user cannot place an order by existing.</p>
     */
    @PostMapping
    public Map<String, Object> create(@RequestBody CreateUserRequest request) {
        currentUser.requireAdmin();
        String email = request.email() == null ? "" : request.email().trim().toLowerCase();
        if (email.isEmpty() || !email.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "an email address is required");
        }
        if (users.findByEmailIgnoreCase(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, email + " is already a user");
        }
        String role = "ADMIN".equalsIgnoreCase(request.role()) ? "ADMIN" : "TRADER";
        String name = request.displayName() == null || request.displayName().isBlank()
                ? email.substring(0, email.indexOf('@')) : request.displayName().trim();

        AppUserEntity created = users.save(new AppUserEntity(email, name, role));
        registry.register(UserId.of(created.getTradingUserId()), name, email);
        log.warn("USER CREATED {} as {} (trading id {}) by {}", email, role,
                created.getTradingUserId(), currentUser.actor());
        return describe(created);
    }

    @PostMapping("/{tradingUserId}/enabled")
    public Map<String, Object> setEnabled(@PathVariable String tradingUserId,
                                          @RequestBody Map<String, Boolean> body) {
        UserId admin = currentUser.requireAdmin();
        AppUserEntity user = find(tradingUserId);
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        if (!enabled && admin.toString().equals(tradingUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "you cannot disable your own account — ask another administrator");
        }
        user.setEnabled(enabled);
        users.save(user);
        if (!enabled) {
            // Removing someone from the allow-list must also stop their engine from opening
            // anything new. Exits are untouched: a disabled user's open position is still managed.
            registry.find(UserId.of(tradingUserId)).ifPresent(a -> a.setEntriesEnabled(false));
        }
        log.warn("USER {} {} by {}", enabled ? "ENABLED" : "DISABLED", user.getEmail(), currentUser.actor());
        return describe(user);
    }

    @PostMapping("/{tradingUserId}/role")
    public Map<String, Object> setRole(@PathVariable String tradingUserId,
                                       @RequestBody Map<String, String> body) {
        UserId admin = currentUser.requireAdmin();
        AppUserEntity user = find(tradingUserId);
        String role = "ADMIN".equalsIgnoreCase(body.get("role")) ? "ADMIN" : "TRADER";
        if ("TRADER".equals(role) && admin.toString().equals(tradingUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "you cannot remove your own administrator role");
        }
        user.setRole(role);
        users.save(user);
        log.warn("USER {} is now {} by {}", user.getEmail(), role, currentUser.actor());
        return describe(user);
    }

    /**
     * Removes a user's account. See the class note for what stays and what is refused.
     *
     * <p>The live registry is dropped only after the store has agreed the user can go, because the
     * store is what knows whether they hold a position.</p>
     */
    @DeleteMapping("/{tradingUserId}")
    public Map<String, Object> delete(@PathVariable String tradingUserId) {
        UserId admin = currentUser.requireAdmin();
        AppUserEntity user = find(tradingUserId);
        UserId id = UserId.of(tradingUserId);
        if (admin.equals(id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "you cannot delete your own account");
        }
        if (allocations.current(id).filter(IpAllocation::isLive).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "release this user's address first — deleting them would leave it bound to nobody");
        }
        String removed;
        try {
            removed = profiles.deleteAccount(id);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
        registry.find(id).ifPresent(a -> a.setEntriesEnabled(false));
        registry.forget(id);
        log.warn("USER DELETED {} (trading id {}) by {} — account removed; trading records kept under the id",
                removed, tradingUserId, currentUser.actor());
        return Map.of("deleted", user.getEmail(), "tradingUserId", tradingUserId);
    }

    // ── Addresses ────────────────────────────────────────────────────────────

    /** Runs the AWS sequence for this user. Refused unless provisioning is on. */
    @PostMapping("/{tradingUserId}/address/provision")
    public Map<String, Object> provision(@PathVariable String tradingUserId) {
        currentUser.requireAdmin();
        AppUserEntity user = find(tradingUserId);
        try {
            allocations.provision(UserId.of(tradingUserId), currentUser.actor());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
        return describe(user);
    }

    public record ManualAddressRequest(String privateIp, String publicIp) {}

    /** Records an address that already exists — set up by hand, or before the automation. */
    @PostMapping("/{tradingUserId}/address/manual")
    public Map<String, Object> adoptManual(@PathVariable String tradingUserId,
                                           @RequestBody ManualAddressRequest request) {
        currentUser.requireAdmin();
        AppUserEntity user = find(tradingUserId);
        if (request.privateIp() == null || request.privateIp().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "the private IP is required");
        }
        try {
            allocations.adoptManual(UserId.of(tradingUserId), request.privateIp().trim(),
                    request.publicIp() == null ? null : request.publicIp().trim(), currentUser.actor());
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        return describe(user);
    }

    @DeleteMapping("/{tradingUserId}/address")
    public Map<String, Object> release(@PathVariable String tradingUserId) {
        currentUser.requireAdmin();
        AppUserEntity user = find(tradingUserId);
        try {
            allocations.release(UserId.of(tradingUserId), currentUser.actor());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
        return describe(user);
    }

    /** The operator's confirmation that the public IP is registered in the Kite developer console. */
    @PostMapping("/{tradingUserId}/address/whitelisted")
    public Map<String, Object> whitelisted(@PathVariable String tradingUserId,
                                           @RequestBody Map<String, Boolean> body) {
        currentUser.requireAdmin();
        AppUserEntity user = find(tradingUserId);
        try {
            allocations.markWhitelisted(UserId.of(tradingUserId),
                    Boolean.TRUE.equals(body.get("whitelisted")), currentUser.actor());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
        log.warn("USER {} address marked {} in Kite by {}", user.getEmail(),
                Boolean.TRUE.equals(body.get("whitelisted")) ? "WHITELISTED" : "not whitelisted",
                currentUser.actor());
        return describe(user);
    }

    // ── Shape ────────────────────────────────────────────────────────────────

    private AppUserEntity find(String tradingUserId) {
        return users.findByTradingUserId(tradingUserId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "no user " + tradingUserId));
    }

    /**
     * One row of the screen. Everything an administrator needs to see whether a user can trade,
     * and nothing that would let them impersonate one — no key, no secret, no token.
     */
    private Map<String, Object> describe(AppUserEntity user) {
        UserId id = UserId.of(user.getTradingUserId());
        Optional<UserAccount> live = registry.find(id);
        UserProfileService.BrokerConfigView broker = profiles.describeBrokerConfig(id);
        Optional<IpAllocation> address = allocations.current(id);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tradingUserId", user.getTradingUserId());
        m.put("email", user.getEmail());
        m.put("displayName", user.getDisplayName());
        m.put("role", user.getRole());
        m.put("enabled", user.isEnabled());
        m.put("createdAt", user.getCreatedAt());
        m.put("lastLoginAt", user.getLastLoginAt());
        m.put("brokerCredentialsSet", broker.apiKeySet() && broker.apiSecretSet());
        m.put("brokerSessionDate", broker.tokenTradingDate());
        m.put("entriesArmed", live.map(UserAccount::entriesEnabled).orElse(false));

        Map<String, Object> a = new LinkedHashMap<>();
        a.put("sourceIp", broker.sourceIp());
        a.put("status", address.map(x -> x.status().name()).orElse("NONE"));
        a.put("privateIp", address.map(IpAllocation::privateIp).orElse(null));
        a.put("publicIp", address.map(IpAllocation::publicIp).orElse(null));
        a.put("automated", address.map(IpAllocation::isAutomated).orElse(false));
        a.put("whitelistedWithBroker", address.map(IpAllocation::whitelistedWithBroker).orElse(false));
        a.put("lastError", address.map(IpAllocation::lastError).orElse(null));
        a.put("updatedAt", address.map(IpAllocation::updatedAt).orElse(null));
        a.put("updatedBy", address.map(IpAllocation::updatedBy).orElse(null));
        m.put("address", a);

        // The one line the screen leads with: what is stopping this user trading, if anything.
        m.put("readiness", readiness(user, broker, address, live));
        m.put("hasExposure", profiles.hasExposure(id));
        return m;
    }

    private static String readiness(AppUserEntity user, UserProfileService.BrokerConfigView broker,
                                    Optional<IpAllocation> address, Optional<UserAccount> live) {
        if (!user.isEnabled()) return "disabled";
        if (!(broker.apiKeySet() && broker.apiSecretSet())) return "needs broker credentials";
        if (address.isEmpty() || !address.get().isLive()) {
            return address.map(a -> a.status().name().equals("FAILED")
                    ? "address provisioning failed" : "needs an address").orElse("needs an address");
        }
        if (!address.get().whitelistedWithBroker()) return "register " + address.get().publicIp() + " in Kite";
        if (broker.tokenTradingDate() == null) return "needs today's Kite login";
        if (!live.map(UserAccount::entriesEnabled).orElse(false)) return "ready — entries disarmed";
        return "trading";
    }
}
