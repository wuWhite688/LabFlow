package com.arthur.labops.auth;

public enum RefreshTokenRevokeReason {
    ROTATED,
    LOGOUT,
    USER_DISABLED,
    REUSE
}
