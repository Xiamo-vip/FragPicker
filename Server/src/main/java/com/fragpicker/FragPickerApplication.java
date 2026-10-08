package com.fragpicker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FragPickerApplication {

    public static void main(String[] args) {
        com.fragpicker.knowledge.embedding.EmbeddingRuntime.prepare();
        SpringApplication.run(FragPickerApplication.class, args);
    }
}
