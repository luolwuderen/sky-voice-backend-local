package com.skyvoice.controller;

import com.skyvoice.config.AppConfig;
import com.skyvoice.tts.TTSProviderFactory;
import com.skyvoice.tts.VoiceCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TTS 音色查询与当前 TTS 配置信息 API。
 * 帮助用户在 POST /api/v1/generate 之前，知道可以选哪些 voice.id 填到 voiceA / voiceB。
 */
@RestController
@RequestMapping("/api/v1/tts")
@RequiredArgsConstructor
public class TTSVoiceController {

    private final TTSProviderFactory ttsFactory;
    private final AppConfig appConfig;

    /**
     * 查询当前激活的 TTS 供应商 + 模型 + 支持的模型/语言/默认音色。
     */
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        var qwen = appConfig.getTts().getQwen();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", ttsFactory.getProvider().getProviderName());
        body.put("activeModel", qwen == null ? null : qwen.getModel());
        body.put("apiUrl", qwen == null ? null : qwen.getApiUrl());
        body.put("supportedModels", VoiceCatalog.supportedModelKeys());
        body.put("supportedLanguages", VoiceCatalog.supportedLanguageCodes());
        body.put("defaultVoices", Map.of(
                "voiceA(zh)", VoiceCatalog.defaultVoiceIdFor(qwen == null ? null : qwen.getModel(), "zh", "A"),
                "voiceB(zh)", VoiceCatalog.defaultVoiceIdFor(qwen == null ? null : qwen.getModel(), "zh", "B"),
                "voiceA(en)", VoiceCatalog.defaultVoiceIdFor(qwen == null ? null : qwen.getModel(), "en", "A"),
                "voiceB(en)", VoiceCatalog.defaultVoiceIdFor(qwen == null ? null : qwen.getModel(), "en", "B")
        ));
        return ResponseEntity.ok(body);
    }

    /**
     * 查询指定模型和语言支持的音色列表。
     *   model: 默认读取当前激活配置（application.yml tts.qwen.model），可覆盖
     *   lang:  可选，按语言过滤（zh/en/ja）
     */
    @GetMapping("/voices")
    public ResponseEntity<Map<String, Object>> listVoices(
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String lang) {

        String actualModel = model;
        if (actualModel == null || actualModel.isBlank()) {
            var qwen = appConfig.getTts().getQwen();
            actualModel = qwen == null ? "cosyvoice-v3-flash" : qwen.getModel();
        }

        List<VoiceCatalog.VoiceInfo> voices = VoiceCatalog.listByModelAndLang(actualModel, lang);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", ttsFactory.getProvider().getProviderName());
        body.put("model", actualModel);
        body.put("filterLanguage", lang == null || lang.isBlank() ? "all" : lang);
        body.put("count", voices.size());
        body.put("defaultVoiceA", VoiceCatalog.defaultVoiceIdFor(actualModel, lang, "A"));
        body.put("defaultVoiceB", VoiceCatalog.defaultVoiceIdFor(actualModel, lang, "B"));
        body.put("voices", voices);
        body.put("usage", "选任一 voice.id，填到 POST /api/v1/generate 的 voiceA / voiceB 字段即可");
        return ResponseEntity.ok(body);
    }
}