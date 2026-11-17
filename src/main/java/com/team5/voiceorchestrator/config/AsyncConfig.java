package com.team5.voiceorchestrator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Configures non-blocking task execution pools for audio processing,
 * STT/TTS provider calls, and AI orchestration turns.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String ORCHESTRATOR_EXECUTOR = "orchestratorTaskExecutor";

    @Bean(name = ORCHESTRATOR_EXECUTOR)
    public Executor orchestratorTaskExecutor(OrchestratorProperties properties) {
        OrchestratorProperties.Async async = properties.getAsync();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(async.getCorePoolSize());
        executor.setMaxPoolSize(async.getMaxPoolSize());
        executor.setQueueCapacity(async.getQueueCapacity());
        executor.setThreadNamePrefix(async.getThreadNamePrefix());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
