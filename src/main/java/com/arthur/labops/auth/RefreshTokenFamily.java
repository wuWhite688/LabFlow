package com.arthur.labops.auth;

import java.time.Instant;

import com.arthur.labops.user.PlatformUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "refresh_token_families")
public class RefreshTokenFamily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private PlatformUser user;

    @Column(name = "current_token_id")
    private Long currentTokenId;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason", length = 32)
    private RefreshTokenRevokeReason revokeReason;

    /**
     * Second-line lost-update detection. Family {@code PESSIMISTIC_WRITE} is the
     * primary correctness guarantee on MySQL; {@code @Version} makes concurrent
     * refresh writers fail on H2 where {@code FOR UPDATE} does not serialize.
     */
    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RefreshTokenFamily() {
    }

    public RefreshTokenFamily(PlatformUser user) {
        this.user = user;
        this.createdAt = Instant.now();
    }

    public boolean isTerminal() {
        return revokedAt != null;
    }

    public void terminate(Instant now, RefreshTokenRevokeReason reason) {
        if (revokedAt != null) {
            return;
        }
        this.revokedAt = now;
        this.revokeReason = reason;
        this.currentTokenId = null;
    }

    public Long getId() {
        return id;
    }

    public PlatformUser getUser() {
        return user;
    }

    public Long getCurrentTokenId() {
        return currentTokenId;
    }

    public void setCurrentTokenId(Long currentTokenId) {
        this.currentTokenId = currentTokenId;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public RefreshTokenRevokeReason getRevokeReason() {
        return revokeReason;
    }

    public long getVersion() {
        return version;
    }
}
