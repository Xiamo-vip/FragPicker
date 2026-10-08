package com.fragpicker.ingestion.preview;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class PreviewLimiterTest {
    @Test void limitsConcurrentProviderCallsAndReleasesSlotsAfterCompletion() throws Exception {
        var limiter=new PreviewLimiter(Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"),ZoneOffset.UTC));
        try(var first=limiter.acquire(1);var second=limiter.acquire(2)) {
            assertThatThrownBy(()->limiter.acquire(3)).isInstanceOf(com.fragpicker.common.api.ApiException.class);
        }
        try(var next=limiter.acquire(3)) {assertThatThrownBy(()->limiter.acquire(1)).isInstanceOf(com.fragpicker.common.api.ApiException.class);}
    }
}
