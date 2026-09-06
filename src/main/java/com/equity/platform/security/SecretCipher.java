package com.equity.platform.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts broker secrets before they reach the database.
 *
 * <p>Putting an API secret or an access token in a table in clear text means anyone who can read the
 * file, a backup, or a stray {@code SELECT} has the ability to trade the account. The sibling engine
 * stores them plain; that is the one pattern from it not worth copying.</p>
 *
 * <p>AES-GCM, which authenticates as well as encrypts — a value tampered with in the database fails
 * to decrypt rather than decrypting into something unexpected. Each value gets its own random IV,
 * stored alongside the ciphertext, so two users with the same API key do not produce identical rows.</p>
 *
 * <h2>The key</h2>
 * <p>Comes from {@code EQUITY_SECRET_KEY} and is never written anywhere. If it is absent the engine
 * still runs — it has to, or a fresh checkout could not start — but it refuses to <b>store</b>
 * secrets, rather than silently storing them in the clear. That is the honest failure: losing the
 * ability to save a credential is recoverable, discovering later that everything was plain is not.</p>
 *
 * <p>Losing the key means every stored credential becomes unreadable and has to be re-entered. That
 * is the correct behaviour, and the reason the key belongs in the environment rather than beside the
 * data it protects.</p>
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);

    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    /** Marks a value this class produced, so a plain legacy value is recognisable rather than garbled. */
    private static final String PREFIX = "enc1:";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${equity.security.secret-key:}") String configuredKey) {
        this.key = (configuredKey == null || configuredKey.isBlank()) ? null : deriveKey(configuredKey);
        if (key == null) {
            log.warn("EQUITY_SECRET_KEY is not set — broker credentials cannot be saved. "
                    + "Set it to enable storing them; they will not be written in clear text.");
        }
    }

    public boolean isConfigured() { return key != null; }

    /**
     * @throws IllegalStateException if no key is configured — deliberately, so a missing key is a
     *                               refusal to save rather than a silent plaintext write
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return plaintext;
        if (key == null) {
            throw new IllegalStateException(
                    "cannot store a credential: EQUITY_SECRET_KEY is not set");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("could not encrypt a credential", e);
        }
    }

    /**
     * @return the plaintext, or null when the value cannot be read — a wrong or rotated key, or a
     *         tampered row. Null rather than an exception because the caller's honest response is
     *         "this user has no usable credential and must re-enter it", not a crash at startup.
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return stored;
        if (!stored.startsWith(PREFIX)) {
            // Written before encryption existed, or by hand. Returned as-is so an operator can
            // migrate it, but never re-saved that way.
            return stored;
        }
        if (key == null) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(combined, IV_BYTES, combined.length - IV_BYTES),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("a stored credential could not be decrypted — the key may have changed. "
                    + "It must be re-entered.");
            return null;
        }
    }

    /** SHA-256 of the configured value, so any length of key material yields a valid AES-256 key. */
    private static SecretKeySpec deriveKey(String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new SecretKeySpec(digest.digest(material.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("could not derive an encryption key", e);
        }
    }
}
