package com.fragpicker.ingestion;

import com.fragpicker.common.api.ApiException;
import com.fragpicker.integration.oss.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FragmentMediaServiceTest {
    @Test void unavailableSignerAndWrongBucketNeverGenerateCapabilities() {
        var mapper = mock(StoredMediaMapper.class);
        var properties = new OssProperties(false, "test-bucket", "oss-cn-shenzhen.aliyuncs.com", 1048576, 1048576, Duration.ofMinutes(5));
        var record = new StoredMediaRecord(2, 1, MediaKind.VIDEO, "other-bucket", "users/1/fragments/2/video/" + "a".repeat(64), 3, "a".repeat(64), "video/mp4");
        when(mapper.find(2, 1, MediaKind.VIDEO)).thenReturn(record);
        var beans = new StaticListableBeanFactory();
        var service = new FragmentMediaService(mapper, beans.getBeanProvider(OssMediaStorage.class), properties);
        assertThatThrownBy(() -> service.get(1, 2, MediaKind.VIDEO)).isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo("MEDIA_UNAVAILABLE"));
        var signer = mock(OssMediaStorage.class); beans.addBean("storage", signer);
        assertThatThrownBy(() -> service.get(1, 2, MediaKind.VIDEO)).isInstanceOf(ApiException.class); verifyNoInteractions(signer);
    }
}
