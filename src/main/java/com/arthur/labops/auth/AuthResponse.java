package com.arthur.labops.auth;

import com.arthur.labops.user.CurrentUserResponse;

public record AuthResponse(
        String tokenType,
        String accessToken,
        long expiresIn,
        String refreshToken,
        CurrentUserResponse user
) {
    public static AuthResponse of(String accessToken, long expiresIn, String refreshToken, CurrentUserResponse user) {
        return new AuthResponse("Bearer", accessToken, expiresIn, refreshToken, user);
    }
}
