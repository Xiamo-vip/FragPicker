package com.fragpicker.integration.parsevideo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.HttpURLConnection;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "fragpicker.integrations.parsevideo", name = "enabled", havingValue = "true")
public class ParseVideoConfiguration {
    @Bean
    ParseVideoClient parseVideoClient(ParseVideoProperties properties, ObjectMapper json) {
        properties.validateEnabled();
        var factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        var builder = RestClient.builder().requestFactory(factory);
        if (properties.username() != null && !properties.username().isBlank()) {
            builder.defaultHeaders(headers -> headers.setBasicAuth(properties.username(), properties.password()));
        }
        return new ParseVideoClient(builder.build(), properties, json);
    }
}
