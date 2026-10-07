package com.fragpicker.digest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods=false)
@Profile("database")
@EnableScheduling
@ConditionalOnProperty(prefix="fragpicker.digest.schedule",name="enabled",havingValue="true")
public class DigestScheduleConfiguration {
    @Bean DigestScheduler digestScheduler(DigestScheduleStore store,DigestScheduleProperties properties) {
        properties.validate(); return new DigestScheduler(store);
    }
}
