package com.fragpicker.ingestion.preview;

import com.fragpicker.integration.media.*;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClients;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import java.time.Duration;

@Profile("database")
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="fragpicker.integrations.parsevideo",name="enabled",havingValue="true")
public class PreviewConfiguration {
    @Bean
    PreviewParser previewParser(com.fragpicker.integration.parsevideo.ParseVideoProperties properties,com.fasterxml.jackson.databind.ObjectMapper json) {
        var bounded=new com.fragpicker.integration.parsevideo.ParseVideoProperties(true,properties.baseUrl(),Duration.ofSeconds(3),Duration.ofSeconds(12),properties.maxResponseBytes(),properties.username(),properties.password());
        return new PreviewParser(new com.fragpicker.integration.parsevideo.ParseVideoConfiguration().parseVideoClient(bounded,json));
    }
    @Bean(destroyMethod="close")
    MediaResourceProbe mediaResourceProbe() {
        var config=RequestConfig.custom().setConnectTimeout(4000).setConnectionRequestTimeout(1000).setSocketTimeout(4000).build();
        var client=HttpClients.custom().setDnsResolver(new PublicNetworkPolicy()).setDefaultRequestConfig(config)
            .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().disableContentCompression()
            .setMaxConnTotal(2).setMaxConnPerRoute(2)
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36").build();
        return new MediaResourceProbe(client,Duration.ofSeconds(12));
    }
}
