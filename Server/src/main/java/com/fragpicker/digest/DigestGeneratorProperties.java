package com.fragpicker.digest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("fragpicker.digest.generator")
public record DigestGeneratorProperties(@DefaultValue("8192") int maxOutputTokens, @DefaultValue("true") boolean jsonOutput) {
    public void validate() { if (maxOutputTokens < 1 || maxOutputTokens > 32768) throw new IllegalStateException("DIGEST_MAX_OUTPUT_TOKENS must be between 1 and 32768"); }
}
