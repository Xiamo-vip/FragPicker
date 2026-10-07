package com.fragpicker;

import com.fragpicker.integration.aliyun.AliyunCredentialsProperties;
import com.fragpicker.integration.chat.ChatModelProperties;
import com.fragpicker.integration.oss.OssProperties;
import com.fragpicker.integration.parsevideo.ParseVideoProperties;
import com.fragpicker.integration.tingwu.TingwuProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.*;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/** Load production YAML directly so test-only disabled overrides cannot hide a default regression. */
class DefaultServiceConfigurationTest {
    private static final Map<String,String> SWITCHES=Map.ofEntries(
        Map.entry("AI_CHAT_ENABLED","integrations.chat"),
        Map.entry("PARSEVIDEO_ENABLED","integrations.parsevideo"),
        Map.entry("OSS_ENABLED","integrations.oss"),
        Map.entry("TINGWU_ENABLED","integrations.tingwu"),
        Map.entry("INGESTION_WORKER_ENABLED","ingestion.worker"),
        Map.entry("MEDIA_WORKER_ENABLED","ingestion.media-worker"),
        Map.entry("TRANSCRIPTION_WORKER_ENABLED","ingestion.transcription-worker"),
        Map.entry("MEDIA_CLEANUP_ENABLED","ingestion.cleanup-worker"),
        Map.entry("KNOWLEDGE_ENRICHMENT_ENABLED","knowledge.enrichment"),
        Map.entry("KNOWLEDGE_INDEX_ENABLED","knowledge.index"),
        Map.entry("DIGEST_WORKER_ENABLED","digest.worker"),
        Map.entry("DIGEST_SCHEDULE_ENABLED","digest.schedule"));

    private StandardEnvironment production(Map<String,Object> overrides) throws IOException {
        var environment=new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        new YamlPropertySourceLoader().load("production",new ClassPathResource("application.yml"))
            .forEach(environment.getPropertySources()::addLast);
        environment.getPropertySources().addFirst(new MapPropertySource("explicit variables",overrides));
        return environment;
    }
    @Test void allServicesAreEnabledWithoutExplicitFlags() throws IOException {
        var environment=production(Map.of());
        SWITCHES.forEach((variable,path)->assertThat(environment.getProperty("fragpicker."+path+".enabled",Boolean.class))
            .as(variable).isTrue());
    }
    @Test void eachFlagCanBeDisabledWithoutChangingOtherDefaults() throws IOException {
        for(String disabled:SWITCHES.keySet()) {
            var environment=production(Map.of(disabled,"false"));
            SWITCHES.forEach((variable,path)->assertThat(environment.getProperty("fragpicker."+path+".enabled",Boolean.class))
                .as(variable+" with "+disabled+" disabled").isEqualTo(!variable.equals(disabled)));
        }
    }
    @Test void enabledDefaultsStillRequireRealRuntimeConfiguration() throws IOException {
        var binder=Binder.get(production(Map.of()));
        assertThatThrownBy(()->binder.bind("fragpicker.integrations.chat",ChatModelProperties.class).get().validateEnabled())
            .hasMessageContaining("AI_CHAT_API_KEY");
        assertThatThrownBy(()->binder.bind("fragpicker.integrations.parsevideo",ParseVideoProperties.class).get().validateEnabled())
            .hasMessageContaining("PARSEVIDEO_BASE_URL");
        assertThatThrownBy(()->binder.bind("fragpicker.integrations.oss",OssProperties.class).get().validate())
            .hasMessageContaining("OSS_BUCKET");
        assertThatThrownBy(()->binder.bind("fragpicker.integrations.tingwu",TingwuProperties.class).get().validate())
            .hasMessageContaining("TINGWU_APP_KEY");
        assertThatThrownBy(()->binder.bind("fragpicker.integrations.aliyun",AliyunCredentialsProperties.class).get().validate())
            .hasMessageContaining("ALIBABA_CLOUD_ACCESS_KEY_ID");
    }
}
