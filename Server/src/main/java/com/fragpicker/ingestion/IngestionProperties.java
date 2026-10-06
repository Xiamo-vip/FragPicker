package com.fragpicker.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("fragpicker.ingestion")
public record IngestionProperties(List<String> allowedHosts) { }
