package com.arthur.labops.auth;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

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
 * <ol>
 *   <li>{@code Sec-Fetch-Site} present: only {@code same-origin} (our own frontend)
 *       and {@code none} (typed or bookmarked navigation) pass. Page script cannot
 *       forge it.</li>
 *   <li>Absent, but {@code Origin} present: an older browser. Every cross-origin POST
 *       carries Origin, so its <em>full</em> origin (scheme, host, port) must be one
 *       of {@code labops.security.trusted-origins}. {@code null} and anything that is
 *       not a bare origin are rejected.</li>
 *   <li>Neither header: not a browser (scripts, health checks), not a CSRF vector.</li>
 * </ol>
 *
 * <p>The backend cannot derive its public origin from the request: behind the BFF
 * the Host it sees is the BFF's upstream address. So the trusted list is
 * configuration, shared with the BFF through the {@code TRUSTED_ORIGINS} variable.
 * {@code Sec-Fetch-Mode} is never consulted: Node's fetch in the BFF adds
 * {@code sec-fetch-mode: cors} on its own when forwarding.
 */
@Component
public class FetchMetadataGuard {

    static final String SITE_HEADER = "Sec-Fetch-Site";
    static final String ORIGIN_HEADER = "Origin";

    private final Set<String> trustedOrigins;

    public FetchMetadataGuard(@Value("${labops.security.trusted-origins}") List<String> trustedOrigins) {
        this.trustedOrigins = trustedOrigins.stream()
                .map(FetchMetadataGuard::normalise)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    public void requireSameOrigin(String secFetchSite, String origin) {
        if (secFetchSite != null && !secFetchSite.isBlank()) {
            String site = secFetchSite.trim();
            if ("same-origin".equalsIgnoreCase(site) || "none".equalsIgnoreCase(site)) {
                return;
            }
            throw blocked();
        }
        if (origin == null || origin.isBlank()) {
            return;
        }
        String normalised = normalise(origin);
        if (normalised == null || !trustedOrigins.contains(normalised)) {
            throw blocked();
        }
    }

    /**
     * {@code scheme://host[:port]} in lower case with the scheme's default port
     * dropped, the way a browser serialises Origin. Returns null for {@code null},
     * unparseable values, and anything carrying a path, query or credentials.
     */
    static String normalise(String origin) {
        if (origin == null) {
            return null;
        }
        String value = origin.trim();
        if (value.isEmpty() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            URI uri = new URI(value);
            if (uri.getScheme() == null || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))) {
                return null;
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
                port = -1;
            }
            return scheme + "://" + host + (port == -1 ? "" : ":" + port);
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private static BusinessException blocked() {
        return new BusinessException(
                "CROSS_SITE_REQUEST_BLOCKED",
                "跨站请求已被拒绝，请从本站页面发起操作",
                HttpStatus.FORBIDDEN);
    }
}
