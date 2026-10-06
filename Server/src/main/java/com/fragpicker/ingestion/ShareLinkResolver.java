package com.fragpicker.ingestion;

import com.fragpicker.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class ShareLinkResolver {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"“”]+", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final String TRAILING_PUNCTUATION = "。；，！、)）]】}〉》.,;!";
    private final Set<String> allowedHosts;

    public ShareLinkResolver(IngestionProperties properties) {
        if (properties.allowedHosts() == null || properties.allowedHosts().isEmpty()) throw new IllegalStateException("INGESTION_ALLOWED_HOSTS must not be empty");
        allowedHosts = properties.allowedHosts().stream().map(host -> host.strip().toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    public URI resolve(String shareText) {
        if (shareText == null || shareText.length() > 4096) throw invalid();
        var matcher = URL.matcher(shareText);
        if (!matcher.find()) throw invalid();
        String value = matcher.group();
        if (matcher.find()) throw new ApiException(HttpStatus.BAD_REQUEST, "MULTIPLE_LINKS", "一次请只投喂一个链接");
        while (!value.isEmpty() && TRAILING_PUNCTUATION.indexOf(value.charAt(value.length() - 1)) >= 0) value = value.substring(0, value.length() - 1);
        try {
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (uri.getUserInfo() != null || !allowedHosts.contains(host)
                    || (uri.getPort() != -1 && uri.getPort() != (scheme.equals("https") ? 443 : 80))) throw invalid();
            String path = uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            return URI.create(URI.create(scheme + "://" + host + path + query).toASCIIString());
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }

    private ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SHARE_LINK", "请提供受支持平台的视频分享链接"); }
}
