package com.team5.voiceorchestrator;

import com.team5.voiceorchestrator.config.OrchestratorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the Team 5 Real-Time Voice &amp; Animation Orchestrator microservice.
 */
@SpringBootApplication
@EnableConfigurationProperties(OrchestratorProperties.class)
public class VoiceAnimationOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(VoiceAnimationOrchestratorApplication.class, args);
    }
}
