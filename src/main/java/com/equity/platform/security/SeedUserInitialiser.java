package com.equity.platform.security;

import com.equity.store.AppUserEntity;
import com.equity.store.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes sure the seed account exists before anyone tries to sign in.
 *
 * <p>The allow-list is the gate, so an empty table locks everybody out — including whoever is
 * supposed to administer it. The reference engine solves that by admitting the first caller when the
 * table is empty, which leaves a window after deployment that anyone reaching the callback could
 * win. Creating the row at startup closes it: the account exists before the first request, so there
 * is no "first caller" to race for.</p>
 *
 * <p>Idempotent, and it never modifies an existing row. If the seed account has since been disabled
 * or demoted, that was somebody's decision and a restart must not quietly undo it.</p>
 */
@Component
public class SeedUserInitialiser implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedUserInitialiser.class);

    private final AppUserRepository users;
    private final String seedEmail;

    public SeedUserInitialiser(AppUserRepository users,
                               @Value("${equity.auth.seed-email:}") String seedEmail) {
        this.users = users;
        this.seedEmail = seedEmail == null ? "" : seedEmail.trim();
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (seedEmail.isBlank()) {
            if (users.count() == 0) {
                log.warn("No users exist and equity.auth.seed-email is not set. With Google sign-in "
                        + "enabled nobody will be able to log in.");
            }
            return;
        }

        users.findByEmailIgnoreCase(seedEmail).ifPresentOrElse(
                existing -> log.info("seed user {} already present as {} ({})",
                        seedEmail, existing.getRole(), existing.isEnabled() ? "enabled" : "DISABLED"),
                () -> {
                    AppUserEntity created = users.save(
                            new AppUserEntity(seedEmail, "Seed administrator", "ADMIN"));
                    log.warn("SEED USER CREATED: {} as ADMIN, trading id {}",
                            seedEmail, created.getTradingUserId());
                });
    }
}
