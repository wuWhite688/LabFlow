package com.arthur.labops.auth;

import com.arthur.labops.user.CurrentUserResponse;

public record AuthResponse(
        String accessToken,
        long expiresIn,
        CurrentUserResponse user
) {
    public static AuthResponse of(String accessToken, long expiresIn, CurrentUserResponse user) {
        return new AuthResponse(accessToken, expiresIn, user);
    }
}
