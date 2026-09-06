package com.equity.domain.user;

import java.util.Set;

public record TradingUser(UserId userId, String displayName, String email,
                          UserStatus status, Set<Role> roles) {
    public boolean isAdmin()      { return roles.contains(Role.ADMIN); }
    public boolean canTakeEntry() { return status == UserStatus.ACTIVE; }
}
