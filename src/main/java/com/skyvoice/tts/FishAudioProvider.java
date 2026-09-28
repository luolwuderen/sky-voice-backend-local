package com.skyvoice.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyvoice.config.AppConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Fish Audio TTS 实现
 * API文档: https://docs.fish.audio
 * 定价: Free(8K credits) / Plus $11/月(250K credits) / Pro $75/月(2M credits)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FishAudioProvider implements TTSProvider {

    private final AppConfig appConfig;
    private final ObjectMapper objectMapper;

    @Override
    public byte[] synthesize(String text, String voiceId, String language) throws TTSException {
        var fishConfig = appConfig.getTts().getFishAudio();
        if (fishConfig == null || fishConfig.getApiKey() == null) {
            throw new TTSException("Fish Audio API key not configured");
        }

        try {
            // Fish Audio API 请求体
            // 文档: https://docs.fish.audio/api-reference/tts
            String requestBody = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
                put("text", text);
                put("reference_id", voiceId);  // 声音ID
                put("format", "mp3");
                put("latency", "normal");     // normal/balanced/quality
            }});

            WebClient client = WebClient.builder()
                    .baseUrl(fishConfig.getApiUrl())
                    .defaultHeader("Authorization", "Bearer " + fishConfig.getApiKey())
                    .defaultHeader("Content-Type", "application/json")
                    .build();

            byte[] audioData = client.post()
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .timeout(Duration.ofSeconds(60))
                    .block();

            if (audioData == null || audioData.length == 0) {
                throw new TTSException("Fish Audio returned empty audio data");
            }

            log.debug("Fish Audio TTS success: {} chars -> {} bytes", text.length(), audioData.length);
            return audioData;

        } catch (Exception e) {
            log.error("Fish Audio TTS failed", e);
            throw new TTSException("Fish Audio TTS failed: " + e.getMessage(), e);
        }
    }

    /**
     * 根据语言和角色获取对应的声音ID
     */
    public String getVoiceId(String language, String speaker, String voiceAOverride, String voiceBOverride) {
        var fishConfig = appConfig.getTts().getFishAudio();
        boolean isSpeakerA = "A".equalsIgnoreCase(speaker);

        return switch (language.toLowerCase()) {
            case "zh", "chinese", "中文" -> isSpeakerA ? fishConfig.getVoiceAZh() : fishConfig.getVoiceBZh();
            case "ja", "japanese", "日本語" -> isSpeakerA ? fishConfig.getVoiceAJa() : fishConfig.getVoiceBJa();
            default -> isSpeakerA ? fishConfig.getVoiceAEn() : fishConfig.getVoiceBEn();
        };
    }

    @Override
    public String getProviderName() {
        return "FishAudio";
    }
}
