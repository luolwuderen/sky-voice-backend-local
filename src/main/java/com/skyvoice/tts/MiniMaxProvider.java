package com.skyvoice.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyvoice.config.AppConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * MiniMax T2A v2 实现
 * API文档: https://platform.minimaxi.com/document/T2A%20V2
 * 模型: speech-2.6-hd（高质量）/ speech-2.6-nano（快速）
 * 支持: 中文/英文/多语种，情感语音
 * 定价: 按字符计费
 *
 * 预置声音 (部分):
 *   中文: female-shaonv, male-qn-qingse, female-yujie, male-qn-jingying
 *   英文: English_expressive_1, English_expressive_2
 *   日文: Japanese_Calm_Woman, Japanese_Calm_Man
 *   完整列表: https://platform.minimaxi.com/document/voice-list
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MiniMaxProvider implements TTSProvider {

    private final AppConfig appConfig;
    private final ObjectMapper objectMapper;

    @Override
    public byte[] synthesize(String text, String voiceId, String language) throws TTSException {
        var ttsConfig = appConfig.getTts().getMiniMax();
        if (ttsConfig == null || ttsConfig.getApiKey() == null) {
            throw new TTSException("MiniMax API key not configured");
        }

        try {
            // 文本长度限制检查
            if (text.length() > 10000) {
                throw new TTSException("Text too long for MiniMax TTS (max 10,000 chars), got " + text.length());
            }

            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", ttsConfig.getModel());
            requestBody.put("text", text);
            requestBody.put("voice_setting", Map.of(
                    "voice_id", voiceId,
                    "speed", 1.0,
                    "vol", 1.0,
                    "pitch", 0
            ));
            requestBody.put("audio_setting", Map.of(
                    "sample_rate", 32000,
                    "bitrate", 128000,
                    "format", "mp3",
                    "channel", 1
            ));
            requestBody.put("stream", false);
            requestBody.put("output_format", "hex");

            WebClient client = WebClient.builder()
                    .baseUrl(ttsConfig.getApiUrl())
                    .defaultHeader("Authorization", "Bearer " + ttsConfig.getApiKey())
                    .defaultHeader("Content-Type", "application/json")
                    .build();

            String responseJson = client.post()
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(60))
                    .block();

            // 解析响应
            JsonNode root = objectMapper.readTree(responseJson);

            // 错误检查：base_resp.status_code 不为 0 表示错误
            JsonNode baseResp = root.path("base_resp");
            int statusCode = baseResp.path("status_code").asInt(0);
            if (statusCode != 0) {
                String statusMsg = baseResp.path("status_msg").asText("Unknown error");
                throw new TTSException("MiniMax TTS error (code=" + statusCode + "): " + statusMsg);
            }

            // 提取 hex 编码的音频数据并解码
            String hexAudio = root.path("data").path("audio").asText("");
            if (hexAudio.isEmpty()) {
                throw new TTSException("No audio data in MiniMax response: " + responseJson);
            }

            byte[] audioData = HexFormat.of().parseHex(hexAudio);

            log.debug("MiniMax TTS success: {} chars -> {} bytes", text.length(), audioData.length);
            return audioData;

        } catch (TTSException e) {
            throw e;
        } catch (Exception e) {
            log.error("MiniMax TTS failed", e);
            throw new TTSException("MiniMax TTS failed: " + e.getMessage(), e);
        }
    }

    /**
     * 根据语言和角色选择声音ID
     * 声音列表: https://platform.minimaxi.com/document/voice-list
     */
    public String getVoiceId(String language, String speaker, String voiceAOverride, String voiceBOverride) {
        var ttsConfig = appConfig.getTts().getMiniMax();
        boolean isSpeakerA = "A".equalsIgnoreCase(speaker);

        // 优先使用配置文件中自定义的声音
        String configured = isSpeakerA ? ttsConfig.getVoiceA() : ttsConfig.getVoiceB();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }

        // 按语言选择默认声音
        return switch (language.toLowerCase()) {
            case "zh", "chinese", "中文" -> {
                String voice = isSpeakerA ? ttsConfig.getVoiceAZh() : ttsConfig.getVoiceBZh();
                yield (voice != null && !voice.isBlank()) ? voice
                        : (isSpeakerA ? "female-shaonv" : "male-qn-qingse");
            }
            case "ja", "japanese", "日本語" -> {
                String voice = isSpeakerA ? ttsConfig.getVoiceAJa() : ttsConfig.getVoiceBJa();
                yield (voice != null && !voice.isBlank()) ? voice
                        : (isSpeakerA ? "Japanese_Calm_Woman" : "Japanese_Calm_Man");
            }
            default -> {
                String voice = isSpeakerA ? ttsConfig.getVoiceAEn() : ttsConfig.getVoiceBEn();
                yield (voice != null && !voice.isBlank()) ? voice
                        : (isSpeakerA ? "English_expressive_1" : "English_expressive_2");
            }
        };
    }

    @Override
    public String getProviderName() {
        return "MiniMax";
    }
}
