package com.equity.store;

import com.equity.broker.kite.KiteSessionPersistence;
import com.equity.domain.user.UserId;
import com.equity.platform.security.SecretCipher;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kite sessions on disk, encrypted.
 *
 * <p>The access token is a bearer credential: whoever holds it can trade the account until it
 * expires. It is therefore encrypted before it reaches the database and decrypted only on the way
 * out, and if no encryption key is configured this class <b>declines to store it</b> rather than
 * writing it in clear. A restart then demands a fresh login, which is the old behaviour and an
 * acceptable one — silently writing a plaintext token would not be.</p>
 */
@Component
public class JpaKiteSessionPersistence implements KiteSessionPersistence {

    private static final Logger log = LoggerFactory.getLogger(JpaKiteSessionPersistence.class);

    private final BrokerConfigRepository repository;
    private final SecretCipher cipher;

    public JpaKiteSessionPersistence(BrokerConfigRepository repository, SecretCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    @Override
    @Transactional
    public void save(StoredSession session) {
        if (!cipher.isConfigured()) {
            log.warn("not persisting the Kite session for user={}: no encryption key is set, and a "
                    + "token will not be written in clear text. A restart will need a fresh login.",
                    session.userId());
            return;
        }
        BrokerConfigEntity config = repository.findByTradingUserId(session.userId().toString())
                .orElseGet(() -> new BrokerConfigEntity(session.userId().toString()));
        config.setBrokerClientId(session.kiteUserId());
        config.setSession(cipher.encrypt(session.accessToken()),
                session.tradingDate(), session.issuedAt());
        repository.save(config);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredSession> load(UserId userId, LocalDate tradingDate) {
        return repository.findByTradingUserId(userId.toString())
                .filter(c -> tradingDate.equals(c.getTokenTradingDate()))
                .map(this::toSession)
                .filter(java.util.Objects::nonNull);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoredSession> loadAll(LocalDate tradingDate) {
        List<StoredSession> out = new ArrayList<>();
        for (BrokerConfigEntity c : repository.findByTokenTradingDate(tradingDate)) {
            StoredSession s = toSession(c);
            if (s != null) out.add(s);
        }
        return out;
    }

    @Override
    @Transactional
    public void clear(UserId userId) {
        repository.findByTradingUserId(userId.toString()).ifPresent(c -> {
            c.clearSession();
            repository.save(c);
        });
    }

    /** Null when the token cannot be decrypted — a rotated key, which means "log in again". */
    private StoredSession toSession(BrokerConfigEntity c) {
        String token = cipher.decrypt(c.getAccessTokenEncrypted());
        if (token == null || token.isBlank()) return null;
        return new StoredSession(UserId.of(c.getTradingUserId()), c.getBrokerClientId(),
                token, c.getTokenTradingDate(), c.getTokenIssuedAt());
    }
}
