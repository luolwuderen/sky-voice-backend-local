package com.skyvoice.tts;

import com.skyvoice.config.AppConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * TTS供应商工厂 — 通过配置文件切换供应商
 *
 * 配置方式：application.yml 中设置
 *   sky-voice:
 *     tts:
 *       active-provider: QwenTTS   # 可选: QwenTTS / FishAudio / MiniMax / ElevenLabs
 *
 * 新增供应商步骤：
 *   1. 实现 TTSProvider 接口，加 @Component 注解
 *   2. 在 AppConfig 中添加对应配置类
 *   3. 在 application.yml 中添加配置项
 *   4. 修改 active-provider 即可切换，无需改 PipelineService
 */
@Slf4j
@Service
public class TTSProviderFactory {

    private final AppConfig appConfig;
    private final Map<String, TTSProvider> providers;

    /**
     * Spring 自动注入所有 TTSProvider 实现
     */
    public TTSProviderFactory(AppConfig appConfig, List<TTSProvider> providerList) {
        this.appConfig = appConfig;
        this.providers = providerList.stream()
                .collect(Collectors.toMap(TTSProvider::getProviderName, Function.identity()));
        log.info("TTS providers registered: {}", providers.keySet());
    }

    /**
     * 获取当前配置的TTS供应商
     */
    public TTSProvider getProvider() {
        String active = appConfig.getTts().getActiveProvider();
        if (active == null || active.isBlank()) {
            active = "QwenTTS";
        }

        TTSProvider provider = providers.get(active);
        if (provider == null) {
            throw new IllegalStateException(
                    "TTS provider not found: " + active + ", available: " + providers.keySet());
        }
        return provider;
    }

    /**
     * 获取所有已注册的供应商名称
     */
    public Map<String, TTSProvider> getAllProviders() {
        return providers;
    }
}
