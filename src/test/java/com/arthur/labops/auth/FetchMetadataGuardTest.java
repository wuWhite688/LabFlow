package com.arthur.labops.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.arthur.labops.common.BusinessException;

class FetchMetadataGuardTest {

    private final FetchMetadataGuard guard = new FetchMetadataGuard(List.of("http://localhost:13000"));

    @Test
    void fetchMetadataDecidesWhenPresentRegardlessOfOrigin() {
        assertThatCode(() -> guard.requireSameOrigin("same-origin", "https://evil.example.com"))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.requireSameOrigin("none", null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.requireSameOrigin("same-site", "http://localhost:13000"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> guard.requireSameOrigin("cross-site", null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void withoutFetchMetadataTheFullOriginMustBeTrusted() {
        assertThatCode(() -> guard.requireSameOrigin(null, "http://localhost:13000")).doesNotThrowAnyException();
        // 大小写与默认端口按 origin 规范化后比较
        assertThatCode(() -> guard.requireSameOrigin(null, "HTTP://LOCALHOST:13000")).doesNotThrowAnyException();

        for (String origin : List.of(
                "https://localhost:13000",   // 只差协议
                "http://localhost:3000",     // 只差端口
                "http://localhost:13000.evil.example.com",
                "null",
                "not a url")) {
            assertThatThrownBy(() -> guard.requireSameOrigin(null, origin))
                    .as(origin)
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void noFetchMetadataAndNoOriginIsANonBrowserClient() {
        assertThatCode(() -> guard.requireSameOrigin(null, null)).doesNotThrowAnyException();
        assertThatCode(() -> guard.requireSameOrigin(" ", "")).doesNotThrowAnyException();
    }

    @Test
    void defaultPortsNormaliseOnBothSides() {
        FetchMetadataGuard https = new FetchMetadataGuard(List.of("https://app.example.com:443"));
        assertThatCode(() -> https.requireSameOrigin(null, "https://app.example.com")).doesNotThrowAnyException();
        assertThat(FetchMetadataGuard.normalise("http://Example.com:80")).isEqualTo("http://example.com");
    }
}
