package com.arthur.labops.user;

public record TechnicianResponse(Long id, String username, String displayName) {
    static TechnicianResponse from(PlatformUser user) {
        return new TechnicianResponse(user.getId(), user.getUsername(), user.getDisplayName());
    }
}
