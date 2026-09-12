package com.equity.store;

import com.equity.broker.kite.KiteCredentials;
import com.equity.broker.kite.KiteCredentialsProvider;
import com.equity.domain.user.UserId;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Bridges the broker's credential lookup to the database.
 *
 * <p>A separate class so the broker package keeps no knowledge of JPA, and so the decryption lives
 * on the store side of the boundary where the key does.</p>
 */
@Component
public class StoredKiteCredentials implements KiteCredentialsProvider.StoredCredentials {

    private final UserProfileService profiles;

    public StoredKiteCredentials(UserProfileService profiles) {
        this.profiles = profiles;
    }

    @Override
    public Optional<KiteCredentials> find(UserId userId) {
        return profiles.brokerCredentials(userId)
                .map(c -> new KiteCredentials(c.apiKey(), c.apiSecret(), c.sourceIp()));
    }
}
