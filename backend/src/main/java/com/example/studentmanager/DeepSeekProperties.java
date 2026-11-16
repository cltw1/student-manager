package com.example.studentmanager;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.ai")
public record DeepSeekProperties(String baseUrl, String apiKey, String model, int timeoutSeconds) {
    public boolean configured() { return apiKey != null && !apiKey.isBlank(); }
}
