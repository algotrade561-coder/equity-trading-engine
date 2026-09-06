package com.equity.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.equity.store.AppUserEntity;
import com.equity.store.AppUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * The allow-list, and the wiring that made it unreachable.
 *
 * <p>Google requests the {@code openid} scope, which makes Spring Security route the login through
 * its OIDC user service and ignore the plain OAuth2 one completely. Only the OAuth2 service was
 * registered, so this check never ran: any Google account got as far as an authenticated session,
 * the engine could not identify it, and the console bounced straight back to the sign-in button.</p>
 *
 * <p>Nothing failed loudly. That is what makes it worth a test rather than a comment.</p>
 */
class GoogleAccountAuthoriserTest {

    /** A repository with one enabled account. */
    private static AppUserRepository repositoryWith(AppUserEntity... accounts) {
        return new StubUserRepository(accounts);
    }

    @Test
    void anAddressOnTheListIsAdmitted() {
        AppUserEntity seed = new AppUserEntity("seed@example.com", "Seed", "ADMIN");
        var authoriser = new GoogleAccountAuthoriser(repositoryWith(seed));

        AppUserEntity account = authoriser.authorise("seed@example.com", true, "Seed");

        assertThat(account.getEmail()).isEqualTo("seed@example.com");
        assertThat(account.getLastLoginAt()).isNotNull();
    }

    @Test
    void anAddressNotOnTheListIsRefused() {
        var authoriser = new GoogleAccountAuthoriser(repositoryWith());

        assertThatThrownBy(() -> authoriser.authorise("stranger@example.com", true, "Stranger"))
                .as("Google authenticates any of its accounts; the table is what grants access")
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("not authorised");
    }

    @Test
    void aDisabledAccountIsRefused() {
        AppUserEntity disabled = new AppUserEntity("gone@example.com", "Gone", "TRADER");
        disabled.setEnabled(false);
        var authoriser = new GoogleAccountAuthoriser(repositoryWith(disabled));

        assertThatThrownBy(() -> authoriser.authorise("gone@example.com", true, "Gone"))
                .hasMessageContaining("disabled");
    }

    @Test
    void anUnverifiedAddressIsRefused() {
        AppUserEntity seed = new AppUserEntity("seed@example.com", "Seed", "ADMIN");
        var authoriser = new GoogleAccountAuthoriser(repositoryWith(seed));

        assertThatThrownBy(() -> authoriser.authorise("seed@example.com", false, "Seed"))
                .as("an unverified address is whatever the account holder typed, so matching one "
                        + "against the list would let somebody claim an address they do not control")
                .hasMessageContaining("not verified");
    }

    @Test
    void aProviderThatOmitsTheVerifiedFlagIsStillAdmitted() {
        AppUserEntity seed = new AppUserEntity("seed@example.com", "Seed", "ADMIN");
        var authoriser = new GoogleAccountAuthoriser(repositoryWith(seed));

        assertThat(authoriser.authorise("seed@example.com", null, "Seed")).isNotNull();
    }

    /**
     * The OIDC service must exist and be an OIDC service.
     *
     * <p>This is the shape of the original bug: the allow-list was implemented, tested and correct,
     * and simply never called because Google's login does not go through the service that held it.
     * Asserting the type is what ties the check to the path Google actually takes.</p>
     */
    @Test
    void theOidcPathIsTheOneGoogleTakesAndItIsImplemented() {
        OAuth2UserService<OidcUserRequest, OidcUser> service =
                new GoogleOidcUserService(new GoogleAccountAuthoriser(repositoryWith()));

        assertThat(service)
                .as("requesting the openid scope routes login through the OIDC user service; "
                        + "registering only the OAuth2 one leaves the allow-list unreachable")
                .isInstanceOf(GoogleOidcUserService.class);
    }

    /** Minimal in-memory repository — the real one needs a database this test has no use for. */
    private static final class StubUserRepository
            implements org.springframework.data.repository.CrudRepository<AppUserEntity, Long>,
                       AppUserRepository {

        private final java.util.List<AppUserEntity> accounts;

        StubUserRepository(AppUserEntity... accounts) {
            this.accounts = new java.util.ArrayList<>(java.util.List.of(accounts));
        }

        @Override public Optional<AppUserEntity> findByEmailIgnoreCase(String email) {
            return accounts.stream().filter(a -> a.getEmail().equalsIgnoreCase(email)).findFirst();
        }

        @Override public Optional<AppUserEntity> findByTradingUserId(String tradingUserId) {
            return accounts.stream()
                    .filter(a -> a.getTradingUserId().equals(tradingUserId)).findFirst();
        }

        @Override public <S extends AppUserEntity> S save(S entity) { return entity; }

        // Nothing below is exercised; the interface simply has to be satisfied.
        @Override public <S extends AppUserEntity> java.util.List<S> saveAll(Iterable<S> e) { throw new UnsupportedOperationException(); }
        @Override public Optional<AppUserEntity> findById(Long id) { return Optional.empty(); }
        @Override public boolean existsById(Long id) { return false; }
        @Override public java.util.List<AppUserEntity> findAll() { return accounts; }
        @Override public java.util.List<AppUserEntity> findAllById(Iterable<Long> ids) { return java.util.List.of(); }
        @Override public long count() { return accounts.size(); }
        @Override public void deleteById(Long id) {}
        @Override public void delete(AppUserEntity entity) {}
        @Override public void deleteAllById(Iterable<? extends Long> ids) {}
        @Override public void deleteAll(Iterable<? extends AppUserEntity> entities) {}
        @Override public void deleteAll() {}
        @Override public void flush() {}
        @Override public <S extends AppUserEntity> S saveAndFlush(S entity) { return entity; }
        @Override public <S extends AppUserEntity> java.util.List<S> saveAllAndFlush(Iterable<S> e) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<AppUserEntity> entities) {}
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) {}
        @Override public void deleteAllInBatch() {}
        @Override public AppUserEntity getOne(Long id) { throw new UnsupportedOperationException(); }
        @Override public AppUserEntity getById(Long id) { throw new UnsupportedOperationException(); }
        @Override public AppUserEntity getReferenceById(Long id) { throw new UnsupportedOperationException(); }
        @Override public <S extends AppUserEntity> Optional<S> findOne(org.springframework.data.domain.Example<S> e) { return Optional.empty(); }
        @Override public <S extends AppUserEntity> java.util.List<S> findAll(org.springframework.data.domain.Example<S> e) { return java.util.List.of(); }
        @Override public <S extends AppUserEntity> java.util.List<S> findAll(org.springframework.data.domain.Example<S> e, org.springframework.data.domain.Sort s) { return java.util.List.of(); }
        @Override public <S extends AppUserEntity> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> e, org.springframework.data.domain.Pageable p) { throw new UnsupportedOperationException(); }
        @Override public <S extends AppUserEntity> long count(org.springframework.data.domain.Example<S> e) { return 0; }
        @Override public <S extends AppUserEntity> boolean exists(org.springframework.data.domain.Example<S> e) { return false; }
        @Override public <S extends AppUserEntity, R> R findBy(org.springframework.data.domain.Example<S> e, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> f) { throw new UnsupportedOperationException(); }
        @Override public java.util.List<AppUserEntity> findAll(org.springframework.data.domain.Sort sort) { return accounts; }
        @Override public org.springframework.data.domain.Page<AppUserEntity> findAll(org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
    }
}
