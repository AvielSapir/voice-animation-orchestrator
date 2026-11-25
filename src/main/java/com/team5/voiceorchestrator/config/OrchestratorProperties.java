package com.team5.voiceorchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed configuration properties for the orchestrator microservice,
 * bound from {@code orchestrator.*} in {@code application.yml}.
 */
@ConfigurationProperties(prefix = "orchestrator")
public class OrchestratorProperties {

    private WebSocket websocket = new WebSocket();
    private Audio audio = new Audio();
    private Vad vad = new Vad();
    private Ai ai = new Ai();
    private Stt stt = new Stt();
    private Tts tts = new Tts();
    private Async async = new Async();

    public WebSocket getWebsocket() {
        return websocket;
    }

    public void setWebsocket(WebSocket websocket) {
        this.websocket = websocket;
    }

    public Audio getAudio() {
        return audio;
    }

    public void setAudio(Audio audio) {
        this.audio = audio;
    }

    public Vad getVad() {
        return vad;
    }

    public void setVad(Vad vad) {
        this.vad = vad;
    }

    public Ai getAi() {
        return ai;
    }

    public void setAi(Ai ai) {
        this.ai = ai;
    }

    public Stt getStt() {
        return stt;
    }

    public void setStt(Stt stt) {
        this.stt = stt;
    }

    public Tts getTts() {
        return tts;
    }

    public void setTts(Tts tts) {
        this.tts = tts;
    }

    public Async getAsync() {
        return async;
    }

    public void setAsync(Async async) {
        this.async = async;
    }

    public static class WebSocket {
        private String endpoint = "/ws/unity";
        private String therapistEndpoint = "/ws/therapist";
        private String allowedOrigins = "*";
        private int maxBinaryMessageSizeBytes = 65536;
        private int maxTextMessageSizeBytes = 16384;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getTherapistEndpoint() {
            return therapistEndpoint;
        }

        public void setTherapistEndpoint(String therapistEndpoint) {
            this.therapistEndpoint = therapistEndpoint;
        }

        public String getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(String allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }

        public int getMaxBinaryMessageSizeBytes() {
            return maxBinaryMessageSizeBytes;
        }

        public void setMaxBinaryMessageSizeBytes(int maxBinaryMessageSizeBytes) {
            this.maxBinaryMessageSizeBytes = maxBinaryMessageSizeBytes;
        }

        public int getMaxTextMessageSizeBytes() {
            return maxTextMessageSizeBytes;
        }

        public void setMaxTextMessageSizeBytes(int maxTextMessageSizeBytes) {
            this.maxTextMessageSizeBytes = maxTextMessageSizeBytes;
        }
    }

    public static class Audio {
        private int sampleRateHz = 16000;
        private int channels = 1;
        private int bitsPerSample = 16;
        private int chunkDurationMs = 20;

        public int getSampleRateHz() {
            return sampleRateHz;
        }

        public void setSampleRateHz(int sampleRateHz) {
            this.sampleRateHz = sampleRateHz;
        }

        public int getChannels() {
            return channels;
        }

        public void setChannels(int channels) {
            this.channels = channels;
        }

        public int getBitsPerSample() {
            return bitsPerSample;
        }

        public void setBitsPerSample(int bitsPerSample) {
            this.bitsPerSample = bitsPerSample;
        }

        public int getChunkDurationMs() {
            return chunkDurationMs;
        }

        public void setChunkDurationMs(int chunkDurationMs) {
            this.chunkDurationMs = chunkDurationMs;
        }
    }

    public static class Vad {
        private long silenceThresholdMs = 1000;
        private double energyThreshold = 0.02;
        private long minSpeechDurationMs = 250;

        public long getSilenceThresholdMs() {
            return silenceThresholdMs;
        }

        public void setSilenceThresholdMs(long silenceThresholdMs) {
            this.silenceThresholdMs = silenceThresholdMs;
        }

        public double getEnergyThreshold() {
            return energyThreshold;
        }

        public void setEnergyThreshold(double energyThreshold) {
            this.energyThreshold = energyThreshold;
        }

        public long getMinSpeechDurationMs() {
            return minSpeechDurationMs;
        }

        public void setMinSpeechDurationMs(long minSpeechDurationMs) {
            this.minSpeechDurationMs = minSpeechDurationMs;
        }
    }

    public static class Ai {
        private String baseUrl = "http://localhost:8081";
        private String turnEndpoint = "/api/v1/turn";
        private long timeoutMs = 5000;
        private String llmProvider = "groq";
        private String llmModel = "qwen/qwen3.8-27b";
        private java.util.List<String> availableLlmModels = new java.util.ArrayList<>(
                java.util.List.of("qwen/qwen3.8-27b", "openai/gpt-oss-120b", "openai/gpt-oss-20b")
        );

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getTurnEndpoint() {
            return turnEndpoint;
        }

        public void setTurnEndpoint(String turnEndpoint) {
            this.turnEndpoint = turnEndpoint;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public String getLlmProvider() {
            return llmProvider;
        }

        public void setLlmProvider(String llmProvider) {
            this.llmProvider = llmProvider;
        }

        public String getLlmModel() {
            return llmModel;
        }

        public void setLlmModel(String llmModel) {
            this.llmModel = llmModel;
        }

        public java.util.List<String> getAvailableLlmModels() {
            return availableLlmModels;
        }

        public void setAvailableLlmModels(java.util.List<String> availableLlmModels) {
            this.availableLlmModels = availableLlmModels;
        }
    }

    public static class Stt {
        private String provider = "groq";
        private String model = "whisper-large-v3-turbo";
        private String apiKey = "";
        private String language = "he";
        private long timeoutMs = 4000;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getLanguage() {
            return language;
        }

        public void setLanguage(String language) {
            this.language = language;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    public static class Tts {
        private String provider = "cartesia";
        private String model = "sonic-3.6";
        private String apiKey = "";
        private String voiceId = "";
        private String language = "he";
        private long timeoutMs = 4000;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getVoiceId() {
            return voiceId;
        }

        public void setVoiceId(String voiceId) {
            this.voiceId = voiceId;
        }

        public String getLanguage() {
            return language;
        }

        public void setLanguage(String language) {
            this.language = language;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    public static class Async {
        private int corePoolSize = 8;
        private int maxPoolSize = 32;
        private int queueCapacity = 256;
        private String threadNamePrefix = "orch-";

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        public void setThreadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
        }
    }
}
