package com.fragpicker.ingestion.deletion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.integration.oss.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
@Profile("database")
@ConditionalOnProperty(prefix="fragpicker.ingestion.cleanup-worker",name="enabled",havingValue="true")
public class CleanupConfiguration {
    @Bean CleanupWorker cleanupWorker(CleanupStore store,ObjectProvider<OssMediaStorage> storage,OssProperties oss,ObjectMapper json) {
        if(!oss.enabled() || storage.getIfAvailable()==null)throw new IllegalStateException("MEDIA_CLEANUP_ENABLED requires OSS_ENABLED and private storage");
        return new CleanupWorker(store,storage.getObject(),json);
    }
}
