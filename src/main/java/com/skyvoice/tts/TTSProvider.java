package com.skyvoice.tts;

/**
 * TTS提供商统一接口 — 适配器模式
 * 支持运行时切换不同TTS API，对冲供应商风险
 */
public interface TTSProvider {

    /**
     * 合成语音
     *
     * @param text     要合成的文本
     * @param voiceId  声音ID
     * @param language 语言代码 (zh, en, ja)
     * @return 音频字节流 (MP3格式)
     */
    byte[] synthesize(String text, String voiceId, String language) throws TTSException;

    /**
     * 根据语言和角色选择声音ID
     *
     * @param language 语言代码
     * @param speaker  角色 ("A" 或 "B")
     * @return 声音ID
     */
    String getVoiceId(String language, String speaker, String voiceAOverride, String voiceBOverride);

    /**
     * 提供商名称 — 用于工厂模式匹配
     */
    String getProviderName();

    class TTSException extends RuntimeException {
        public TTSException(String message, Throwable cause) {
            super(message, cause);
        }
        public TTSException(String message) {
            super(message);
        }
    }
}
