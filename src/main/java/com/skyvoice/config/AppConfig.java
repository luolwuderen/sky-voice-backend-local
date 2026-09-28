package com.skyvoice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Sky Voice 全局配置 — 绑定 application.yml 里 sky-voice.* 的所有子树。
 *
 * 字段命名约定：和 YAML 的 key 完全对齐（除了从 kebab-case 转 camelCase）。
 *   YAML:  sky-voice.tts.qwen.api-key  ->  Java: appConfig.getTts().getQwen().getApiKey()
 * 任何字段名错位（例如 YAML 写 qwen，Java 写成 ttsConfig）都会导致绑定时子树为 null，
 * 进而抛出 "Qwen TTS API key not configured" 等错误。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "sky-voice")
public class AppConfig {

    private LlmConfig llm;
    private TtsConfig tts;
    private AudioConfig audio;
    private LimitsConfig limits;
    private IapConfig iap;

    @Data
    public static class LlmConfig {
        private String apiUrl;
        private String apiKey;
        private String model;
        private double temperature;
        private int maxTokens;
    }

    @Data
    public static class TtsConfig {
        /** 当前使用的供应商: QwenTTS / FishAudio / MiniMax / ElevenLabs */
        private String activeProvider = "QwenTTS";
        /** 对齐 YAML 里的 "qwen" 段：@ConfigurationProperties 按字段名绑定；getter 会生成为 getQwen() */
        private QwenTTSConfig qwen;
        private FishAudioConfig fishAudio;
        private MiniMaxConfig miniMax;
        private ElevenLabsConfig elevenLabs;
    }

    /**
     * 阿里云 Qwen-Audio-TTS / CosyVoice 配置
     * 获取API Key: https://bailian.console.aliyun.com/ -> 模型广场 -> API Key
     * 获取WorkspaceId: 百炼控制台 -> 工作空间设置
     */
    @Data
    public static class QwenTTSConfig {
        private String apiUrl;      // 完整endpoint，含workspaceId
                                    // 北京: https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer
                                    // 新加坡: https://{WorkspaceId}.ap-southeast-1.maas.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer
        private String apiKey;      // DashScope API Key
        private String model;       // qwen-audio-3.0-tts-flash / qwen-audio-3.0-tts-plus / cosyvoice-v3-flash
        private String voiceA;      // 主持人A声音ID（留空用默认）
        private String voiceB;      // 主持人B声音ID（留空用默认）
    }

    /**
     * Fish Audio TTS 配置
     * 注册: https://fish.audio
     * 定价: Free(8K credits) / Plus $11/月(250K credits)
     */
    @Data
    public static class FishAudioConfig {
        private String apiUrl = "https://api.fish.audio/v1/tts";
        private String apiKey;
        private String voiceAZh;
        private String voiceBZh;
        private String voiceAEn;
        private String voiceBEn;
        private String voiceAJa;
        private String voiceBJa;
    }

    /**
     * MiniMax T2A 配置
     * 注册: https://platform.minimaxi.com
     * 模型: speech-2.6-hd
     * 支持: 中文/英文/多语种，情感语音
     */
    @Data
    public static class MiniMaxConfig {
        private String apiUrl = "https://api.minimax.chat/v1/t2a_v2";
        private String apiKey;
        private String model = "speech-2.6-hd";
        private String voiceA;   // voice_id（全局，留空则按语言选择）
        private String voiceB;
        // 按语言配置独立声音
        private String voiceAZh;  // 中文主持人A声音ID
        private String voiceBZh;  // 中文主持人B声音ID
        private String voiceAEn;  // 英文主持人A声音ID
        private String voiceBEn;  // 英文主持人B声音ID
        private String voiceAJa;  // 日文主持人A声音ID
        private String voiceBJa;  // 日文主持人B声音ID
    }

    /**
     * ElevenLabs TTS 配置
     * 注册: https://elevenlabs.io
     * 模型: eleven_multilingual_v2 / eleven_turbo_v2
     * 海外主流TTS，语种最全，但价格较高
     */
    @Data
    public static class ElevenLabsConfig {
        private String apiUrl = "https://api.elevenlabs.io/v1/text-to-speech";
        private String apiKey;
        private String model = "eleven_multilingual_v2";
        private String voiceA;   // voice_id（全局，留空则按语言选择）
        private String voiceB;
        // 按语言配置独立声音
        private String voiceAZh;  // 中文主持人A声音ID
        private String voiceBZh;  // 中文主持人B声音ID
        private String voiceAEn;  // 英文主持人A声音ID
        private String voiceBEn;  // 英文主持人B声音ID
        private String voiceAJa;  // 日文主持人A声音ID
        private String voiceBJa;  // 日文主持人B声音ID
    }

    @Data
    public static class AudioConfig {
        private String outputDir;
        private int segmentSilenceMs;
        private int fadeDurationMs;
    }

    @Data
    public static class LimitsConfig {
        private int freeMonthly;
        private int proMonthly;
        private int maxInputChars;
    }

    /**
     * Apple IAP 配置
     * sharedSecret: App Store Connect → My Apps → App Information → App-Specific Shared Secret
     */
    @Data
    public static class IapConfig {
        private String sharedSecret;
    }
}
