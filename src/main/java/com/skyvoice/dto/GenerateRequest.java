package com.skyvoice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class GenerateRequest {

    @NotBlank(message = "Input type is required")
    private String inputType;  // TEXT, URL, PDF

    // inputType=TEXT 时填这里
    @Size(max = 30000, message = "Input text too long (max 30000 chars)")
    private String content;

    // inputType=URL 时填这里
    private String url;

    // 目标语言：zh, en, ja
    @NotBlank(message = "Language is required")
    private String language;

    // 期望音频时长（分钟），默认5分钟
    private Integer targetMinutes = 5;

    // 指定 Speaker A 的音色（可选），留空=按模型+语言自动选。
    // 可用列表：GET /api/v1/tts/voices?model=...&lang=...
    private String voiceA;

    // 指定 Speaker B 的音色（可选），留空=按模型+语言自动选
    private String voiceB;
}
