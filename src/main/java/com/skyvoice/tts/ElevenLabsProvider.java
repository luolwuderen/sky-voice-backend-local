package com.skyvoice.tts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyvoice.config.AppConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * ElevenLabs TTS 实现
 * API文档: https://elevenlabs.io/docs/api-reference/text-to-speech
 * 模型: eleven_multilingual_v2（多语种）/ eleven_turbo_v2（快速）
 * 支持: 英文/中文/日文等29种语言
 * 定价: Free(10K credits) / Starter $5/月(30K) / Creator $22/月(100K)
 *
 * 预置声音 (部分):
 *   Rachel  — 21m00Tcm4TlvDq8ikWAM (英文女声，温柔)
 *   Domi    — AZnzlk1XvdvUeBnXlFj2 (英文女声，活力)
 *   Bella   — EXAVITQu4vr4xnSDxMAC (英文女声)
 *   Antoni  — ErXwobaYiN019PkySvjV (英文男声)
 *   完整列表: https://elevenlabs.io/docs/api-reference/voices
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ElevenLabsProvider implements TTSProvider {

    private final AppConfig appConfig;
    private final ObjectMapper objectMapper;

    @Override
    public byte[] synthesize(String text, String voiceId, String language) throws TTSException {
        var ttsConfig = appConfig.getTts().getElevenLabs();
        if (ttsConfig == null || ttsConfig.getApiKey() == null) {
            throw new TTSException("ElevenLabs API key not configured");
        }

        try {
            // 文本长度限制检查
            if (text.length() > 10000) {
                throw new TTSException("Text too long for ElevenLabs TTS (max 10,000 chars), got " + text.length());
            }

            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("text", text);
            requestBody.put("model_id", ttsConfig.getModel());
            requestBody.put("voice_settings", Map.of(
                    "stability", 0.5,
                    "similarity_boost", 0.75,
                    "style", 0.0,
                    "use_speaker_boost", true
            ));

            // API URL 需要拼接 voiceId: {apiUrl}/{voiceId}
            String fullUrl = ttsConfig.getApiUrl() + "/" + voiceId;

            // 注意: ElevenLabs 用 xi-api-key header 而非 Bearer
            WebClient client = WebClient.builder()
                    .baseUrl(fullUrl)
                    .defaultHeader("xi-api-key", ttsConfig.getApiKey())
                    .defaultHeader("Content-Type", "application/json")
                    .defaultHeader("Accept", "audio/mpeg")
                    .build();

            // ElevenLabs 直接返回音频字节流（mp3），不是 JSON
            byte[] audioData = client.post()
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .timeout(Duration.ofSeconds(60))
                    .block();

            if (audioData == null || audioData.length == 0) {
                throw new TTSException("ElevenLabs returned empty audio data");
            }

            log.debug("ElevenLabs TTS success: {} chars -> {} bytes", text.length(), audioData.length);
            return audioData;

        } catch (TTSException e) {
            throw e;
        } catch (Exception e) {
            log.error("ElevenLabs TTS failed", e);
            throw new TTSException("ElevenLabs TTS failed: " + e.getMessage(), e);
        }
    }

    /**
     * 根据语言和角色选择声音ID
     * 预置声音列表: https://elevenlabs.io/docs/api-reference/voices
     */
    public String getVoiceId(String language, String speaker, String voiceAOverride, String voiceBOverride) {
        var ttsConfig = appConfig.getTts().getElevenLabs();
        boolean isSpeakerA = "A".equalsIgnoreCase(speaker);

        // 优先使用配置文件中自定义的声音
        String configured = isSpeakerA ? ttsConfig.getVoiceA() : ttsConfig.getVoiceB();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }

        // ElevenLabs multilingual 模型对所有语言使用相同的预置声音
        return switch (language.toLowerCase()) {
            case "zh", "chinese", "中文" -> {
                String voice = isSpeakerA ? ttsConfig.getVoiceAZh() : ttsConfig.getVoiceBZh();
                yield (voice != null && !voice.isBlank()) ? voice
                        : (isSpeakerA ? "21m00Tcm4TlvDq8ikWAM" : "AZnzlk1XvdvUeBnXlFj2");
            }
            case "ja", "japanese", "日本語" -> {
                String voice = isSpeakerA ? ttsConfig.getVoiceAJa() : ttsConfig.getVoiceBJa();
                yield (voice != null && !voice.isBlank()) ? voice
                        : (isSpeakerA ? "21m00Tcm4TlvDq8ikWAM" : "AZnzlk1XvdvUeBnXlFj2");
            }
            default -> {
                String voice = isSpeakerA ? ttsConfig.getVoiceAEn() : ttsConfig.getVoiceBEn();
                yield (voice != null && !voice.isBlank()) ? voice
                        : (isSpeakerA ? "21m00Tcm4TlvDq8ikWAM" : "AZnzlk1XvdvUeBnXlFj2");
            }
        };
    }

    @Override
    public String getProviderName() {
        return "ElevenLabs";
    }
}
