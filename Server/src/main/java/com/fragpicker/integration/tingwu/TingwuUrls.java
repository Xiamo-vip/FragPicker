package com.fragpicker.integration.tingwu;

import java.net.URI;

final class TingwuUrls {
    private TingwuUrls() { }
    static URI requirePublicOss(String value) {
        try {
            var uri = URI.create(value);
            String host = uri.getHost();
            if (value.length() > 16384 || host == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || !host.matches("[a-z0-9][a-z0-9.-]*\\.oss-[a-z0-9-]+\\.aliyuncs\\.com")
                    || host.contains("-internal.") || uri.getRawPath() == null || uri.getRawPath().isEmpty()
                    || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || (uri.getPort() != -1 && uri.getPort() != ("https".equals(uri.getScheme()) ? 443 : 80))) {
                throw new IllegalArgumentException();
            }
            // OSS signatures do not include the scheme. Keep the raw path/query intact when upgrading.
            return "http".equals(uri.getScheme())
                    ? URI.create("https://" + host + uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())) : uri;
        } catch (RuntimeException invalid) { throw new TingwuFailure(TingwuFailure.Code.INVALID_RESPONSE, false); }
    }
}
