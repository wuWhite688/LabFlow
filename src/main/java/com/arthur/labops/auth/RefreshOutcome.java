package com.arthur.labops.auth;

/**
 * Result of a committed refresh-token transaction. {@link AuthService} maps
 * this to HTTP <em>after</em> the transaction has returned, so a reuse
 * revocation cannot roll back with a {@code BusinessException}.
 */
public record RefreshOutcome(Kind kind, IssuedAuthSession session) {

    public enum Kind {
        ISSUED,
        REUSED,
        INVALID,
        DISABLED
    }

    static RefreshOutcome issued(IssuedAuthSession session) {
        return new RefreshOutcome(Kind.ISSUED, session);
    }

    static RefreshOutcome reused() {
        return new RefreshOutcome(Kind.REUSED, null);
    }

    static RefreshOutcome invalid() {
        return new RefreshOutcome(Kind.INVALID, null);
    }

    static RefreshOutcome disabled() {
        return new RefreshOutcome(Kind.DISABLED, null);
    }
}
