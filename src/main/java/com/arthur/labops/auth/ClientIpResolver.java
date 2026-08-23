package com.arthur.labops.auth;

import java.net.InetAddress;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the address stamped by the browser-facing BFF. The BFF overwrites
 * this header; the backend must remain on loopback or a private service network.
 */
final class ClientIpResolver {

    static final String BFF_CLIENT_IP_HEADER = "X-BFF-Client-IP";

    private ClientIpResolver() {
    }

    static String resolve(HttpServletRequest request) {
        String remote = normalize(request.getRemoteAddr());
        if (isTrustedProxy(remote)) {
            String forwarded = normalize(request.getHeader(BFF_CLIENT_IP_HEADER));
            if (forwarded != null) {
                return forwarded;
            }
        }
        return remote != null ? remote : "unknown";
    }

    private static boolean isTrustedProxy(String address) {
        if (address == null) {
            return false;
        }
        try {
            InetAddress parsed = InetAddress.getByName(address);
            byte[] bytes = parsed.getAddress();
            boolean uniqueLocalV6 = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
            return parsed.isLoopbackAddress()
                    || parsed.isSiteLocalAddress()
                    || parsed.isLinkLocalAddress()
                    || uniqueLocalV6;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        if (candidate.isEmpty() || candidate.length() > 45) {
            return null;
        }
        if (candidate.indexOf(':') >= 0) {
            if (!candidate.matches("[0-9a-fA-F:.]+")) {
                return null;
            }
            try {
                return InetAddress.getByName(candidate).getHostAddress();
            } catch (Exception ignored) {
                return null;
            }
        }

        String[] octets = candidate.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        StringBuilder normalized = new StringBuilder();
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3 || !octet.chars().allMatch(Character::isDigit)) {
                return null;
            }
            int number = Integer.parseInt(octet);
            if (number > 255) {
                return null;
            }
            if (!normalized.isEmpty()) {
                normalized.append('.');
            }
            normalized.append(number);
        }
        return normalized.toString();
    }
}
