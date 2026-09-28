package com.skyvoice.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时配置健康检查 — 在应用完全加载后立刻打印关键配置并做校验。
 * 作用：避免用户启动后跑任务才报 "Qwen TTS API key not configured" 这种错误。
 *
 * 日志里会显示：
 *   - 当前 active TTS provider
 *   - TTS provider 的配置（apiUrl 是否为空、apiKey 是否有值 — 只打前 8 位 + ****，不泄露完整 key）
 *   - LLM apiKey 是否存在
 *   - Audio output 目录路径
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupConfigLogger implements CommandLineRunner {

    private final AppConfig appConfig;

    @Override
    public void run(String... args) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=========================================\n")
          .append(" Sky Voice Backend — Config Health Check\n")
          .append("=========================================\n");

        // LLM
        var llm = appConfig.getLlm();
        sb.append(String.format(
                "[LLM] url=%s, model=%s, apiKey=%s%n",
                llm == null ? "NULL" : maskUrl(llm.getApiUrl()),
                llm == null ? "NULL" : llm.getModel(),
                ok(llm != null && llm.getApiKey() != null && !llm.getApiKey().isBlank(),
                        llm == null ? null : llm.getApiKey())
        ));

        // TTS active provider
        var tts = appConfig.getTts();
        String active = tts == null ? null : tts.getActiveProvider();
        sb.append(String.format("[TTS] active-provider=%s%n", active == null ? "NULL (default QwenTTS)" : active));

        var qwen = tts == null ? null : tts.getQwen();
        sb.append(String.format(
                "[TTS/Qwen] model=%s, apiUrl=%s, apiKey=%s, voice-a=%s, voice-b=%s%n",
                qwen == null ? "NULL" : qwen.getModel(),
                qwen == null ? "NULL" : maskUrl(qwen.getApiUrl()),
                ok(qwen != null && qwen.getApiKey() != null && !qwen.getApiKey().isBlank(),
                        qwen == null ? null : qwen.getApiKey()),
                qwen == null ? null : qwen.getVoiceA(),
                qwen == null ? null : qwen.getVoiceB()
        ));

        if ("FishAudio".equalsIgnoreCase(active)) {
            var fish = tts.getFishAudio();
            sb.append(String.format(
                    "[TTS/FishAudio] url=%s, apiKey=%s%n",
                    fish == null ? "NULL" : fish.getApiUrl(),
                    ok(fish != null && fish.getApiKey() != null && !fish.getApiKey().isBlank(),
                            fish == null ? null : fish.getApiKey())
            ));
        }
        if ("MiniMax".equalsIgnoreCase(active)) {
            var mm = tts.getMiniMax();
            sb.append(String.format(
                    "[TTS/MiniMax] url=%s, model=%s, apiKey=%s%n",
                    mm == null ? "NULL" : mm.getApiUrl(),
                    mm == null ? "NULL" : mm.getModel(),
                    ok(mm != null && mm.getApiKey() != null && !mm.getApiKey().isBlank(),
                            mm == null ? null : mm.getApiKey())
            ));
        }
        if ("ElevenLabs".equalsIgnoreCase(active)) {
            var el = tts.getElevenLabs();
            sb.append(String.format(
                    "[TTS/ElevenLabs] url=%s, model=%s, apiKey=%s%n",
                    el == null ? "NULL" : el.getApiUrl(),
                    el == null ? "NULL" : el.getModel(),
                    ok(el != null && el.getApiKey() != null && !el.getApiKey().isBlank(),
                            el == null ? null : el.getApiKey())
            ));
        }

        // Audio
        var audio = appConfig.getAudio();
        sb.append(String.format(
                "[Audio] output-dir=%s, segment-silence=%dms, fade=%dms%n",
                audio == null ? "NULL" : audio.getOutputDir(),
                audio == null ? -1 : audio.getSegmentSilenceMs(),
                audio == null ? -1 : audio.getFadeDurationMs()
        ));

        // 致命配置缺失提醒（加粗但不阻断启动 — 允许 LLM/TTS 先空着，有报错日志）
        StringBuilder warnings = new StringBuilder();
        if (qwen == null) warnings.append("  - tts.qwen 段缺失或未绑定（AppConfig.getTts().getQwen() == null）").append("\n");
        else {
            if (qwen.getApiKey() == null || qwen.getApiKey().isBlank())
                warnings.append("  - tts.qwen.api-key 缺失（DashScope API Key）").append("\n");
            if (qwen.getApiUrl() == null || qwen.getApiUrl().isBlank())
                warnings.append("  - tts.qwen.api-url 缺失").append("\n");
        }
        if (llm == null || llm.getApiKey() == null || llm.getApiKey().isBlank())
            warnings.append("  - llm.api-key 缺失或为空（DeepSeek / LLM provider API Key）").append("\n");

        if (warnings.length() > 0) {
            sb.append("\n⚠️  配置缺失（会导致生成任务失败，请修正）:\n").append(warnings);
        } else {
            sb.append("\n✅ 关键配置检查通过\n");
        }
        sb.append("=========================================\n");

        if (warnings.length() > 0) {
            log.warn(sb.toString());
        } else {
            log.info(sb.toString());
        }
    }

    /** (ok=true) -> "XXXX.... (OK)"，否则 "MISSING ❌"，避免泄露完整 key。 */
    private static String ok(boolean present, String secret) {
        if (!present) return "MISSING \u274C";
        if (secret == null || secret.length() <= 8) return secret + "*** \u2705";
        return secret.substring(0, 8) + "**** \u2705";
    }

    /** URL 太长时只保留 scheme+host，路径截断成 ...（不包含敏感信息，主要是便于读） */
    private static String maskUrl(String url) {
        if (url == null || url.length() < 40) return url;
        try {
            java.net.URI u = java.net.URI.create(url);
            return u.getScheme() + "://" + u.getHost() + "/...[truncated, len=" + url.length() + "]";
        } catch (Exception e) {
            return url.substring(0, 40) + "...[truncated, len=" + url.length() + "]";
        }
    }
}
