package com.fragpicker.integration.oss;

import com.aliyun.oss.*;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.fragpicker.integration.aliyun.AliyunCredentialsProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "fragpicker.integrations.oss", name = "enabled", havingValue = "true")
public class OssConfiguration {
    @Bean(destroyMethod = "shutdown")
    public OSS ossClient(OssProperties properties, AliyunCredentialsProperties credentials) {
        properties.validate(); credentials.validate();
        var conf = new ClientBuilderConfiguration();
        conf.setSignatureVersion(SignVersion.V4);
        conf.setConnectionTimeout(5000); conf.setSocketTimeout(60000);
        conf.setMaxConnections(4); conf.setMaxErrorRetry(0);
        conf.setCrcCheckEnabled(true);
        return OSSClientBuilder.create().endpoint(properties.normalizedEndpoint()).region(properties.region())
                .credentialsProvider(new DefaultCredentialProvider(credentials.accessKeyId(), credentials.accessKeySecret(), credentials.securityToken()))
                .clientConfiguration(conf).build();
    }

    @Bean
    OssMediaStorage ossMediaStorage(OSS client, OssProperties properties) {
        var storage = new OssMediaStorage(client, properties, Clock.systemUTC());
        storage.verifyPrivateBucket();
        return storage;
    }
}
