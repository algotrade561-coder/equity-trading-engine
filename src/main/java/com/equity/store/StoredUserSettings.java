package com.equity.store;

import com.equity.domain.user.UserId;
import com.equity.user.UserRegistry;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Hydrates a user's account from the database instead of the shipped defaults. */
@Component
public class StoredUserSettings implements UserRegistry.SettingsSource {

    private final UserSettingsRepository repository;

    public StoredUserSettings(UserSettingsRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Loaded> load(UserId userId) {
        return repository.findByTradingUserId(userId.toString())
                .map(e -> new Loaded(e.toRiskLimits(), e.toThresholds(), e.toExitPolicy()));
    }
}
