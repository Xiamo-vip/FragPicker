package com.fragpicker.integration.aliyun;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fragpicker.integrations.aliyun")
public record AliyunCredentialsProperties(String accessKeyId, String accessKeySecret, String securityToken) {
    public void validate() {
        if (accessKeyId == null || accessKeyId.isBlank() || accessKeySecret == null || accessKeySecret.isBlank()) {
            throw new IllegalStateException("ALIBABA_CLOUD_ACCESS_KEY_ID and ALIBABA_CLOUD_ACCESS_KEY_SECRET are required");
        }
    }
    @Override public String toString() { return "AliyunCredentialsProperties[REDACTED]"; }
}
