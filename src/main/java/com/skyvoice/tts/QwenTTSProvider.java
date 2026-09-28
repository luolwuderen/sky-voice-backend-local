package com.skyvoice.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyvoice.audio.AudioConcatenator;
import com.skyvoice.config.AppConfig;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 阿里云百炼 Qwen-Audio-TTS / CosyVoice 非实时语音合成 HTTP 实现。
 *
 * 请求体结构（严格按官方文档放置字段，否则 400）：
 *   {
 *     "model": "cosyvoice-v3-flash"  | "qwen-audio-3.0-tts-flash" | ...,
 *     "input":  { "text": "...", "voice": "longanyang" },
 *     "parameters": { "format": "mp3", "sample_rate": 24000 }
 *   }
 * 注意：format / sample_rate 在 parameters 内，voice / text 在 input 内，不能搞反。
 *
 * 音色与模型匹配（否则 418 Engine return error code）：
 *   - CosyVoice V3 (cosyvoice-v3-flash / plus): longanyang, longanhuan, *_v3 ...
 *   - CosyVoice V2: 必须 *_v2 后缀
 *   - Qwen-Audio-3.0: longanhuan_v3.6, loongeva_v3.6, loongjohn ...
 *   查询官方列表: GET /api/v1/tts/voices?model=...&lang=...
 *
 * 预置声音 (部分):
 *   CosyVoice V3 ZH: longanyang (阳光大男孩), longanhuan (欢脱元气女)
 *   完整列表: https://help.aliyun.com/zh/model-studio/cosyvoice-voice-list
 *   Qwen-Audio-3.0 列表: https://help.aliyun.com/zh/model-studio/qwen-audio-tts-voice-list
 */
@Component
@RequiredArgsConstructor
public class QwenTTSProvider implements TTSProvider {

    private static final Logger log = LoggerFactory.getLogger(QwenTTSProvider.class);

    /** 单条 TTS 请求的文本上限（保守值），超过后会自动按标点切分，避免 400 TextTooLong。 */
    private static final int MAX_TEXT_CHARS_PER_REQUEST = 1000;

    /** 分句正则：保留句号/问号/感叹号/换行等断句标点，否则会把语义切开在中间导致语调奇怪。 */
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？!?；;\n])");

    private final AppConfig appConfig;
    private final ObjectMapper objectMapper;
    private final AudioConcatenator audioConcatenator;

    @Override
    public byte[] synthesize(String text, String voiceId, String language) throws TTSException {
        var ttsConfig = appConfig.getTts().getQwen();
        if (ttsConfig == null || ttsConfig.getApiKey() == null || ttsConfig.getApiKey().isBlank()) {
            throw new TTSException("Qwen TTS API key not configured — check sky-voice.tts.qwen.api-key in application.yml or env QWEN_TTS_API_KEY");
        }

        try {
            // 1) 过长文本按句子切分，逐条合成 —— 注意：合成出来的是多段独立 MP3！
            //    一定不能直接用 ByteArrayOutputStream.write(byte[]) 拼接 MP3 字节，
            //    播放器只认第一段的 Xing/VBRI TOC 帧索引表，会截断后面所有内容！
            List<String> segments = splitTextIfNeeded(text);
            if (segments.size() == 1) {
                return synthesizeOneSegment(segments.get(0), voiceId, ttsConfig);
            }
            log.info("Text too long ({} chars), split into {} segments for TTS — will use ffmpeg-based PCM merge",
                    text.length(), segments.size());
            List<byte[]> audios = new ArrayList<>(segments.size());
            for (int i = 0; i < segments.size(); i++) {
                String seg = segments.get(i);
                log.info("TTS long-text segment {}/{}: {} chars", i + 1, segments.size(), seg.length());
                byte[] audio = synthesizeOneSegment(seg, voiceId, ttsConfig);
                audios.add(audio);
            }
            return audioConcatenator.concatenateMp3(audios, 0);
        } catch (TTSException e) {
            throw e;
        } catch (Exception e) {
            log.error("Qwen TTS failed", e);
            throw new TTSException("Qwen TTS failed: " + e.getMessage(), e);
        }
    }

    /** 单段 TTS 合成（不超过 MAX_TEXT_CHARS_PER_REQUEST）。 */
    private byte[] synthesizeOneSegment(String text, String voiceId, AppConfig.QwenTTSConfig ttsConfig)
            throws TTSException {
        Map<String, Object> requestBody = Map.of(
                "model", ttsConfig.getModel() == null ? "cosyvoice-v3-flash" : ttsConfig.getModel(),
                "input", Map.of(
                        "text", text,
                        "voice", voiceId
                ),
                "parameters", Map.of(
                        "format", "mp3",
                        "sample_rate", 24000
                )
        );

        String apiUrl = ttsConfig.getApiUrl();

        WebClient client = WebClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader("Authorization", "Bearer " + ttsConfig.getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();

        String requestJson;
        try {
            requestJson = objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new TTSException("Serialize Qwen TTS request failed", e);
        }

        // 请求前打组合日志（下次 400/418 时先看这行）
        log.info("Qwen TTS request: model={}, voice={}, textLen={}, api={}",
                ttsConfig.getModel(), voiceId, text.length(), apiUrl);

        String responseJson;
        try {
            responseJson = client.post()
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(120))
                    .block();
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            log.error("Qwen TTS HTTP {}: status={} request(model={}, voice={}, text(前200)={}) responseBody={}",
                    e.getStatusCode(), e.getStatusCode().value(),
                    ttsConfig.getModel(), voiceId,
                    text.substring(0, Math.min(text.length(), 200)),
                    e.getResponseBodyAsString());
            throw new TTSException("Qwen TTS HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e);
        }

        // 解析响应：优先 output.audio.url，其次 output.url，最后 base64 output.audio.data
        JsonNode root;
        try {
            root = objectMapper.readTree(responseJson);
        } catch (Exception e) {
            throw new TTSException("Parse Qwen TTS response failed: " + responseJson, e);
        }

        JsonNode outputNode = root.path("output");
        String audioUrl = outputNode.path("audio").path("url").asText(outputNode.path("url").asText(""));

        if (audioUrl.isEmpty()) {
            String audioBase64 = outputNode.path("audio").path("data").asText("");
            if (!audioBase64.isEmpty()) {
                byte[] data = java.util.Base64.getDecoder().decode(audioBase64);
                log.debug("Qwen TTS inline audio: {} chars -> {} bytes", audioBase64.length(), data.length);
                return data;
            }
            // 代码错误：没拿到音频数据，打完整响应
            log.error("Qwen TTS no audio data, full response: {}", responseJson);
            throw new TTSException("No audio data or url in response: " + responseJson);
        }

        // OSS 预签名 URL 下载：使用 JDK 原生 HttpURLConnection，确保 URL 一字不改（否则 SignatureDoesNotMatch）。
        boolean hasPreSigned = audioUrl.contains("Signature=") || audioUrl.contains("OSSAccessKeyId=");
        if (hasPreSigned && audioUrl.startsWith("http:")) {
            log.warn("OSS pre-signed URL uses HTTP; signature was generated over the EXACT URL string. " +
                    "We will NOT auto-upgrade to HTTPS (would cause SignatureDoesNotMatch). " +
                    "If OSS returns 403, resubmit the task to get a fresh URL. url(前100)={}",
                    audioUrl.substring(0, Math.min(audioUrl.length(), 100)));
        }
        log.info("Downloading audio from url(前100)={}",
                audioUrl.substring(0, Math.min(audioUrl.length(), 100)));

        byte[] audioData;
        try {
            audioData = downloadBytesExact(audioUrl, 60_000);
        } catch (java.io.IOException e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            String hint = hasPreSigned
                    ? " (OSS pre-signed URL detected — signature is for the EXACT URL string. " +
                    "Common causes: URL Expires timestamp exceeded, URL was modified/copy-pasted, " +
                    "bucket policy forces HTTPS but URL is HTTP (resubmit task to get new URL). " +
                    "Try curl -v '" + audioUrl.substring(0, Math.min(audioUrl.length(), 200)) + "' locally.)"
                    : "";
            log.error("Qwen TTS audio download failed: msg={}{}", msg, hint);
            throw new TTSException("Qwen TTS audio download failed: " + msg + hint, e);
        }

        if (audioData == null || audioData.length == 0) {
            throw new TTSException("Downloaded audio is empty from url(前100)=" +
                    audioUrl.substring(0, Math.min(audioUrl.length(), 100)));
        }
        log.debug("Qwen TTS success: {} chars text -> {} bytes audio", text.length(), audioData.length);
        return audioData;
    }

    /**
     * 严格按照阿里云官方文档的模型→音色映射返回 voice 参数。
     * 完整音色列表：GET /api/v1/tts/voices?model=...&lang=...
     *
     * 优先级 (最高→最低):
     *   1) voiceAOverride / voiceBOverride — 本次 POST /generate 请求体里的 voiceA / voiceB
     *   2) application.yml sky-voice.tts.qwen.voice-a / voice-b
     *   3) 模型 + 语言 + speaker 默认查表
     */
    @Override
    public String getVoiceId(String language, String speaker, String voiceAOverride, String voiceBOverride) {
        var ttsConfig = appConfig.getTts().getQwen();
        boolean isSpeakerA = "A".equalsIgnoreCase(speaker);

        // Level 1 — 用户本次请求覆盖（最高优先级）
        String override = isSpeakerA ? voiceAOverride : voiceBOverride;
        if (override != null && !override.isBlank()) {
            log.debug("Using user-requested voice (speaker={}): {}", speaker, override);
            return override;
        }

        // Level 2 — application.yml 全局配置
        String configured = isSpeakerA ? ttsConfig.getVoiceA() : ttsConfig.getVoiceB();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }

        // Level 3 — 模型 + 语言 + speaker 查表兜底
        String model = ttsConfig.getModel() == null ? "" : ttsConfig.getModel().toLowerCase();
        boolean isEn = language.toLowerCase().startsWith("en");
        boolean isJa = language.toLowerCase().startsWith("ja");

        boolean isQwenAudio3 = model.startsWith("qwen-audio-3.0-tts");
        boolean isCosyV2 = model.contains("cosyvoice-v2");

        if (isQwenAudio3) {
            // qwen-audio-3.0-tts-{flash,plus} 官方精品音色
            if (isEn) return isSpeakerA ? "loongjohn" : "loongeva_v3.6";
            if (isJa) return isSpeakerA ? "qwen-audio-3.0-tts-flash-male-jp" : "qwen-audio-3.0-tts-flash-female-jp";
            return isSpeakerA ? "longanhuan_v3.6" : "longjielidou_v3.6";
        }

        if (isCosyV2) {
            // cosyvoice-v2: 强制 _v2 后缀
            if (isJa) return isSpeakerA ? "longbaozhu_v2" : "longxiaoxing_v2";
            if (isEn) return isSpeakerA ? "longwan_v2" : "longxiaochun_v2";
            return isSpeakerA ? "longxiaochun_v2" : "longwan_v2";
        }

        // 默认: CosyVoice V3（flash/plus），标杆音色 longanyang / longanhuan（官方列表有，双语）
        if (isJa) return isSpeakerA ? "longyang_v3" : "longling_v3";
        if (isEn) return isSpeakerA ? "longanyang" : "longanhuan";
        return isSpeakerA ? "longanyang" : "longanhuan";
    }

    @Override
    public String getProviderName() {
        return "QwenTTS";
    }

    // ===================== helpers =====================

    /** 超过长度上限就按句子切分（句子太长时再按逗号切），保证每段都 <= MAX_TEXT_CHARS_PER_REQUEST。 */
    static List<String> splitTextIfNeeded(String text) {
        List<String> result = new ArrayList<>();
        if (text == null) return List.of("");
        if (text.length() <= MAX_TEXT_CHARS_PER_REQUEST) return List.of(text);

        String[] sentences = SENTENCE_SPLIT.split(text);
        StringBuilder buf = new StringBuilder();
        for (String s : sentences) {
            if (buf.length() + s.length() <= MAX_TEXT_CHARS_PER_REQUEST) {
                buf.append(s);
                continue;
            }
            if (!buf.isEmpty()) { result.add(buf.toString()); buf.setLength(0); }
            // 单句超过上限：再强制按长度硬切
            if (s.length() <= MAX_TEXT_CHARS_PER_REQUEST) {
                buf.append(s);
            } else {
                for (int i = 0; i < s.length(); i += MAX_TEXT_CHARS_PER_REQUEST) {
                    int end = Math.min(i + MAX_TEXT_CHARS_PER_REQUEST, s.length());
                    result.add(s.substring(i, end));
                }
            }
        }
        if (!buf.isEmpty()) result.add(buf.toString());
        return result;
    }

    /**
     * 使用 JDK 原生 HttpURLConnection 下载，保证：
     * 1) URL 一字不改（OSS 预签名是对完整 URL 字符串 + Method + Expires 签的，改任何字符都会 SignatureDoesNotMatch）
     * 2) 不添加自定义 Authorization / X-* 头（避免和 URL 里预签名冲突）
     * 3) 失败时能拿到 OSS 的 XML 错误体（一起抛出来便于定位）
     */
    private static byte[] downloadBytesExact(String urlString, int timeoutMs) throws java.io.IOException {
        URL url = URI.create(urlString).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestMethod("GET");
        conn.setUseCaches(false);

        int code = conn.getResponseCode();
        if (code >= 200 && code < 300) {
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        }

        String errorBody;
        try (InputStream err = conn.getErrorStream()) {
            if (err == null) { errorBody = ""; }
            else {
                try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = err.read(buf)) > 0) out.write(buf, 0, n);
                    errorBody = out.toString(java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        throw new java.io.IOException("HTTP " + code + (errorBody.isEmpty() ? "" : ": " + errorBody));
    }
}
