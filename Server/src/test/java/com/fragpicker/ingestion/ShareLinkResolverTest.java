package com.fragpicker.ingestion;

import com.fragpicker.common.api.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShareLinkResolverTest {
    private final ShareLinkResolver resolver = new ShareLinkResolver(new IngestionProperties(List.of("v.douyin.com", "www.bilibili.com", "xhslink.com")));

    @Test
    void extractsShareTextAndNormalizesOnlySafeUrlParts() {
        assertThat(resolver.resolve("分享这个课程（HTTPS://WWW.BILIBILI.COM:443/video/test?a=1&b=2#part）。").toString())
                .isEqualTo("https://www.bilibili.com/video/test?a=1&b=2");
        assertThat(resolver.resolve("看看这个 https://v.douyin.com/example/ 复制链接打开").getHost()).isEqualTo("v.douyin.com");
        assertThat(resolver.resolve("https://xhslink.com/example").getHost()).isEqualTo("xhslink.com");
    }

    @Test
    void rejectsLocalUnsupportedCredentialedAndAmbiguousInputs() {
        for (String value : List.of("https://localhost/a", "http://127.0.0.1/a", "https://www.bilibili.com.example.test/a",
                "https://user:pass@www.bilibili.com/a", "https://www.bilibili.com:8080/a", "just text",
                "https://v.douyin.com/a https://xhslink.com/b")) {
            assertThatThrownBy(() -> resolver.resolve(value)).isInstanceOf(ApiException.class);
        }
    }
}
