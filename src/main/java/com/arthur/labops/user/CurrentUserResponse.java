package com.arthur.labops.user;

public record CurrentUserResponse(
        Long id,
        String username,
        String displayName,
        UserRole role
) {
    public static CurrentUserResponse from(PlatformUser user) {
        return new CurrentUserResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole());
    }
}
