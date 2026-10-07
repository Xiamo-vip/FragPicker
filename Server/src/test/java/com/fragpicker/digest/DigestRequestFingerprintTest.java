package com.fragpicker.digest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.integration.chat.ChatModelProperties;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class DigestRequestFingerprintTest {
    private final ObjectMapper json=new ObjectMapper();
    private ChatModelProperties provider(String model) { return new ChatModelProperties(true,"https://test.invalid","unused",model,Duration.ofSeconds(60),4096); }
    private ChatRequest request(String data,int tokens) { return ChatRequest.builder().messages(SystemMessage.from("instructions"),UserMessage.from(data)).parameters(ChatRequestParameters.builder().maxOutputTokens(tokens).responseFormat(ResponseFormat.JSON).build()).build(); }
    @Test void mapOrderDoesNotChangeRecoveryIdentityButInputOrderModelAndBudgetDo() {
        var first=request("{\"date\":\"2026-10-06\",\"documents\":[{\"id\":1,\"text\":\"导数\"}],\"ids\":[1,2]}",8192);
        var reordered=request("{\"ids\":[1,2],\"documents\":[{\"text\":\"导数\",\"id\":1}],\"date\":\"2026-10-06\"}",8192);
        String hash=DigestRequestFingerprint.hash(first,provider("test-model"),json);
        assertThat(DigestRequestFingerprint.hash(reordered,provider("test-model"),json)).isEqualTo(hash);
        assertThat(DigestRequestFingerprint.hash(first,provider("changed-model"),json)).isNotEqualTo(hash);
        assertThat(DigestRequestFingerprint.hash(request("{\"ids\":[2,1]}",8192),provider("test-model"),json)).isNotEqualTo(hash);
        assertThat(DigestRequestFingerprint.hash(request("{\"ids\":[1,2]}",2048),provider("test-model"),json)).isNotEqualTo(hash);
    }
}
