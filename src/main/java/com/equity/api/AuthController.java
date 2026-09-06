package com.equity.api;

import com.equity.platform.security.CurrentUser;
import com.equity.store.AppUserEntity;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who the browser is talking as.
 *
 * <p>The single endpoint the front end calls before anything else. It answers three questions at
 * once — is sign-in even switched on, is somebody signed in, and what may they do — because the page
 * needs all three to decide between rendering the app, a sign-in prompt, or an admin section.</p>
 *
 * <p>Note what it does not do: accept a user id. Identity comes from the session and nowhere else.
 * The previous console typed a UUID into a text field, which in a multi-user trading engine means
 * one user can operate another's account by editing it.</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final CurrentUser currentUser;

    public AuthController(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("authEnabled", currentUser.isAuthEnabled());

        java.util.Optional<AppUserEntity> account = currentUser.account();
        if (account.isEmpty()) {
            m.put("authenticated", false);
            // The path the page sends the browser to. Spring Security owns it, so the front end
            // never has to know how OAuth is wired.
            m.put("loginUrl", "/oauth2/authorization/google");
            return m;
        }

        AppUserEntity user = account.get();
        m.put("authenticated", true);
        m.put("tradingUserId", user.getTradingUserId());
        m.put("email", user.getEmail());
        m.put("name", user.getDisplayName());
        m.put("role", user.getRole());
        m.put("admin", user.isAdmin());
        return m;
    }
}
