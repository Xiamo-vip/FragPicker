package com.fragpicker.integration.media;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MediaDownloadConfiguration {
    @Bean(destroyMethod = "close")
    SafeMediaDownloader safeMediaDownloader(MediaDownloadProperties properties) {
        properties.validate();
        var requestConfig = RequestConfig.custom().setConnectTimeout((int) properties.connectTimeout().toMillis())
                .setConnectionRequestTimeout((int) properties.connectTimeout().toMillis())
                .setSocketTimeout((int) properties.readTimeout().toMillis()).build();
        var client = HttpClients.custom().setDnsResolver(new PublicNetworkPolicy()).setDefaultRequestConfig(requestConfig)
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().disableContentCompression()
                .setMaxConnTotal(4).setMaxConnPerRoute(2)
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36")
                .build();
        return new SafeMediaDownloader(client, properties);
    }
}
