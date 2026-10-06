package com.fragpicker.integration.tingwu;

import com.aliyun.tingwu20230930.Client;
import com.aliyun.teaopenapi.models.Config;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.integration.aliyun.AliyunCredentialsProperties;
import com.fragpicker.integration.media.PublicNetworkPolicy;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClients;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "fragpicker.integrations.tingwu", name = "enabled", havingValue = "true")
public class TingwuConfiguration {
    @Bean public Client tingwuSdk(TingwuProperties properties, AliyunCredentialsProperties credentials) throws Exception {
        properties.validate(); credentials.validate();
        var config = new Config().setRegionId("cn-beijing").setEndpoint("tingwu.cn-beijing.aliyuncs.com").setProtocol("HTTPS")
                .setAccessKeyId(credentials.accessKeyId()).setAccessKeySecret(credentials.accessKeySecret())
                .setSecurityToken(credentials.securityToken()).setConnectTimeout((int) properties.connectTimeout().toMillis())
                .setReadTimeout((int) properties.readTimeout().toMillis()).setMaxIdleConns(4).setEnableUsageDataCollection(false);
        return new Client(config);
    }
    @Bean public TingwuClient tingwuClient(Client sdk, TingwuProperties properties) { return new TingwuClient(sdk, properties); }
    @Bean(destroyMethod = "close") public TingwuResultReader tingwuResultReader(TingwuProperties properties, ObjectMapper json) {
        properties.validate();
        var request = RequestConfig.custom().setConnectTimeout((int) properties.connectTimeout().toMillis())
                .setConnectionRequestTimeout((int) properties.connectTimeout().toMillis())
                .setSocketTimeout((int) properties.readTimeout().toMillis()).build();
        var client = HttpClients.custom().setDnsResolver(new PublicNetworkPolicy()).setDefaultRequestConfig(request)
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().disableContentCompression()
                .setMaxConnTotal(4).setMaxConnPerRoute(2).build();
        return new TingwuResultReader(client, properties, json);
    }
}
