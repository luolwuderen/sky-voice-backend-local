package com.skyvoice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyvoice.config.AppConfig;
import com.skyvoice.model.DialogueScript;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM服务 — 调用DeepSeek/OpenAI生成双主持人对话脚本
 */
@Service
@RequiredArgsConstructor
public class LLMService {

    private static final Logger log = LoggerFactory.getLogger(LLMService.class);

    private final AppConfig appConfig;
    private final ObjectMapper objectMapper;

    /**
     * 将文章内容转化为双主持人对话脚本
     *
     * @param inputText      原文
     * @param language       目标语言 (zh/en/ja)
     * @param targetMinutes  目标时长（分钟）
     * @return 对话脚本
     */
    public DialogueScript generateDialogueScript(String inputText, String language, int targetMinutes) {
        var llmConfig = appConfig.getLlm();
        if (llmConfig == null || llmConfig.getApiKey() == null) {
            throw new RuntimeException("LLM API key not configured");
        }

        try {
            // 加载system prompt模板
            String promptTemplate = loadPromptTemplate();
            String languageName = getLanguageName(language);
            int targetChars = estimateTargetChars(language, targetMinutes);

            // 填充模板
            String systemPrompt = promptTemplate
                    .replace("{language}", languageName)
                    .replace("{target_minutes}", String.valueOf(targetMinutes))
                    .replace("{target_chars}", String.valueOf(targetChars))
                    .replace("{input_text}", inputText);

            // 构建API请求
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", llmConfig.getModel());
            requestBody.put("temperature", llmConfig.getTemperature());
            requestBody.put("max_tokens", llmConfig.getMaxTokens());

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            // 用户消息可以留空，因为内容已在system prompt中
            messages.add(Map.of("role", "user", "content", "Generate the podcast dialogue now."));
            requestBody.put("messages", messages);

            WebClient client = WebClient.builder()
                    .baseUrl(llmConfig.getApiUrl())
                    .defaultHeader("Authorization", "Bearer " + llmConfig.getApiKey())
                    .defaultHeader("Content-Type", "application/json")
                    .build();

            String responseJson = client.post()
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(120))
                    .block();

            // 解析响应 — 提取choices[0].message.content
            var root = objectMapper.readTree(responseJson);
            String content = root.path("choices").get(0).path("message").path("content").asText();

            // 清理可能的markdown包裹
            content = content.trim();
            if (content.startsWith("```")) {
                content = content.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "");
            }

            // 解析JSON数组为DialogueScript
            List<DialogueScript.DialogueTurn> turns = objectMapper.readValue(
                    content, new TypeReference<>() {});

            int totalChars = turns.stream()
                    .mapToInt(t -> t.getText() == null ? 0 : t.getText().length())
                    .sum();
            int actualSeconds = (int) Math.ceil(totalChars /
                    (estimateTargetChars(language, 1) / 60.0));
            log.info("Generated dialogue script: {} turns, {} total chars, estimate ~{}s (asked for {}min ≈ {} chars)",
                    turns.size(), totalChars, actualSeconds, targetMinutes, targetChars);
            if (totalChars < targetChars * 0.6) {
                log.warn("Generated script SHORT of target! Got {} chars, expected >= {} ({} min target). " +
                        "LLM may be ignoring the length requirement — consider increasing max_tokens or retrying.",
                        totalChars, targetChars, targetMinutes);
            }
            return new DialogueScript(turns);

        } catch (Exception e) {
            log.error("Failed to generate dialogue script", e);
            throw new RuntimeException("LLM generation failed: " + e.getMessage(), e);
        }
    }

    private String loadPromptTemplate() throws Exception {
        try (var is = new ClassPathResource("prompts/dialogue_system.txt").getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String getLanguageName(String code) {
        return switch (code.toLowerCase()) {
            case "zh", "chinese", "中文" -> "Chinese (中文)";
            case "ja", "japanese", "日本語" -> "Japanese (日本語)";
            default -> "English";
        };
    }

    /**
     * 估算目标字符数（按真实 TTS 朗读语速估算，宁多勿少）
     * 中文 ~250字/分钟（CosyVoice 默认语速实测值，150 字/分钟太保守会导致生成的脚本 1/3 时长就播完）
     * 日文 ~220字/分钟
     * 英文 ~900字符/分钟（≈180词/分钟）
     */
    private int estimateTargetChars(String language, int minutes) {
        double charsPerMinute = switch (language.toLowerCase()) {
            case "zh", "chinese", "中文" -> 250.0;
            case "ja", "japanese", "日本語" -> 220.0;
            default -> 900.0;
        };
        return (int) (charsPerMinute * minutes);
    }
}
