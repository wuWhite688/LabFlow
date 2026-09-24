package com.arthur.labops.auth;

import org.springframework.http.HttpStatus;

import com.arthur.labops.common.BusinessException;

/**
 * CSRF defence for the endpoints that read or write the refresh cookie.
 *
 * <p>Everything else authenticates with a Bearer header, which a browser never
 * attaches on its own. login / refresh / logout are different: the refresh cookie
 * rides along automatically. {@code SameSite=Lax} keeps cross-site POSTs out, but it
 * is decided per <em>site</em> (registrable domain), so a page on a sibling
 * subdomain is same-site and its POST still carries the cookie.
 *
 * <p>{@code Sec-Fetch-Site} is set by the browser and cannot be forged by page
 * script. Only {@code same-origin} (our own frontend) and {@code none} (typed or
 * bookmarked navigation) are allowed. A missing header means a non-browser client,
 * which is not a CSRF vector, so it is allowed. The BFF enforces the same rule with
 * an Origin fallback; this is the second line for a backend reached without it.
 */
public final class FetchMetadataGuard {

    static final String HEADER = "Sec-Fetch-Site";

    private FetchMetadataGuard() {
    }

    public static void requireSameOrigin(String secFetchSite) {
        if (secFetchSite == null || secFetchSite.isBlank()) {
            return;
        }
        String site = secFetchSite.trim();
        if ("same-origin".equalsIgnoreCase(site) || "none".equalsIgnoreCase(site)) {
            return;
        }
        throw new BusinessException(
                "CROSS_SITE_REQUEST_BLOCKED",
                "跨站请求已被拒绝，请从本站页面发起操作",
                HttpStatus.FORBIDDEN);
    }
}
