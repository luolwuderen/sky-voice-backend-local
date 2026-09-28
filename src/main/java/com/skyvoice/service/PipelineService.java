package com.skyvoice.service;

import com.skyvoice.audio.AudioConcatenator;
import com.skyvoice.config.AppConfig;
import com.skyvoice.model.DialogueScript;
import com.skyvoice.model.GenerationTask;
import com.skyvoice.tts.TTSProvider;
import com.skyvoice.tts.TTSProviderFactory;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 核心管线服务 — 编排整个生成流程
 *
 * 流程：输入文本 → LLM生成对话脚本 → 逐段TTS合成 → 音频拼接 → 输出MP3
 */
@Service
@RequiredArgsConstructor
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    private final LLMService llmService;
    private final TTSProviderFactory ttsFactory;
    private final AudioConcatenator audioConcatenator;
    private final AppConfig appConfig;

    // MVP阶段用内存存储任务状态，后续换PostgreSQL
    private final ConcurrentHashMap<String, GenerationTask> taskStore = new ConcurrentHashMap<>();

    // 任务超时时间（分钟）— 超过此时间仍未完成的任务标记为FAILED
    private static final int TASK_TIMEOUT_MINUTES = 3;

    /**
     * 创建生成任务（同步返回taskId，异步执行）
     */
    public GenerationTask createTask(String userId, String inputType, String language) {
        GenerationTask task = GenerationTask.create(userId, inputType, language);
        taskStore.put(task.getTaskId(), task);
        return task;
    }

    /**
     * 异步执行生成管线
     *
     * @param voiceAOverride 用户请求级 Speaker A 音色覆盖（null 或空=走默认映射）
     * @param voiceBOverride 用户请求级 Speaker B 音色覆盖
     */
    @Async
    public void executePipeline(String taskId, String inputText, String language, int targetMinutes,
                               String voiceAOverride, String voiceBOverride, boolean isPro) {
        GenerationTask task = taskStore.get(taskId);
        if (task == null) return;

        try {
            // Step 1: LLM生成对话脚本
            task.setStatus(GenerationTask.Status.GENERATING_SCRIPT);
            taskStore.put(taskId, task);

            log.info("[{}] Generating dialogue script...", taskId);
            DialogueScript script = llmService.generateDialogueScript(inputText, language, targetMinutes);
            int scriptTotalChars = script.getTurns().stream()
                    .mapToInt(t -> t.getText() == null ? 0 : t.getText().length()).sum();
            int scriptEstSec = script.estimateDurationSeconds(language);
            log.info("[{}] Script generated: {} turns, {} chars, estimate ~{}s ({} min), target was {} min",
                    taskId, script.getTurns().size(), scriptTotalChars,
                    scriptEstSec, String.format("%.1f", scriptEstSec / 60.0),
                    targetMinutes);
            if (script.getTurns().isEmpty()) {
                throw new IllegalStateException("LLM returned 0 dialogue turns — cannot synthesize empty script");
            }

            // Step 2: 逐段TTS合成
            task.setStatus(GenerationTask.Status.SYNTHESIZING_AUDIO);
            taskStore.put(taskId, task);

            TTSProvider ttsProvider = ttsFactory.getProvider();
            log.info("[{}] Using TTS provider: {}", taskId, ttsProvider.getProviderName());
            if ((voiceAOverride != null && !voiceAOverride.isBlank()) || (voiceBOverride != null && !voiceBOverride.isBlank())) {
                log.info("[{}] User voice override: voiceA={}, voiceB={}", taskId, voiceAOverride, voiceBOverride);
            }

            List<byte[]> audioSegments = new ArrayList<>();
            long totalAudioBytesBefore = 0;
            for (int i = 0; i < script.getTurns().size(); i++) {
                DialogueScript.DialogueTurn turn = script.getTurns().get(i);
                int turnChars = turn.getText() == null ? 0 : turn.getText().length();
                log.info("[{}] Synthesizing turn {}/{} (speaker {}, {} chars, text(前50)={})",
                        taskId, i + 1, script.getTurns().size(), turn.getSpeaker(), turnChars,
                        turn.getText() == null ? "" : turn.getText().substring(0, Math.min(50, turnChars)));

                // 更新进度
                task.setProgress((i + 1) + "/" + script.getTurns().size());
                taskStore.put(taskId, task);

                String voiceId = ttsProvider.getVoiceId(language, turn.getSpeaker(), voiceAOverride, voiceBOverride);
                log.info("[{}] Resolved voice for speaker {} -> {}", taskId, turn.getSpeaker(), voiceId);
                byte[] audio = ttsProvider.synthesize(turn.getText(), voiceId, language);
                if (audio == null || audio.length == 0) {
                    log.warn("[{}] Turn {}/{} returned EMPTY audio bytes! Skipping, will not insert silence.",
                            taskId, i + 1, script.getTurns().size());
                    continue;
                }
                log.info("[{}] Turn {}/{} done: {} bytes audio (≈ {} KB)",
                        taskId, i + 1, script.getTurns().size(), audio.length, audio.length / 1024);
                totalAudioBytesBefore += audio.length;
                audioSegments.add(audio);
            }
            if (audioSegments.isEmpty()) {
                throw new IllegalStateException("All " + script.getTurns().size() + " TTS segments returned empty — aborting");
            }
            log.info("[{}] All {} TTS segments synthesized successfully: {} total bytes ({} KB)",
                    taskId, audioSegments.size(), totalAudioBytesBefore, totalAudioBytesBefore / 1024);

            // Step 3: 音频拼接
            task.setStatus(GenerationTask.Status.CONCATENATING);
            taskStore.put(taskId, task);

            int silenceMs = appConfig.getAudio().getSegmentSilenceMs();

            // AI 合规水印（仅 Free 用户）— 在音频开头添加 AI 生成声明
            if (!isPro) {
                log.info("[{}] Adding AI watermark for free user", taskId);
                byte[] watermark = generateWatermark(ttsProvider, language);
                if (watermark != null && watermark.length > 0) {
                    audioSegments.add(0, watermark);
                    // 水印后加静音间隔
                    audioSegments.add(1, new byte[0]); // 占位，拼接时会插入静音
                }
            }

            log.info("[{}] Concatenating {} audio segments...", taskId, audioSegments.size());
            byte[] finalAudio = audioConcatenator.concatenateMp3(audioSegments, silenceMs);
            long finalKB = finalAudio.length == 0 ? 0 : finalAudio.length / 1024;
            log.info("[{}] Concatenation done: {} bytes ({} KB) final MP3 (sum of segments alone was {} KB)",
                    taskId, finalAudio.length, finalKB, totalAudioBytesBefore / 1024);

            // Step 4: 保存文件
            Path outputDir = Path.of(appConfig.getAudio().getOutputDir());
            Files.createDirectories(outputDir);
            String filename = taskId + ".mp3";
            Path outputPath = outputDir.resolve(filename);

            try (FileOutputStream fos = new FileOutputStream(outputPath.toFile())) {
                fos.write(finalAudio);
            }

            // Step 5: 完成
            int duration = script.estimateDurationSeconds(language);
            task.setStatus(GenerationTask.Status.DONE);
            task.setAudioUrl("/api/v1/audio/" + filename);
            task.setDurationSeconds(duration);
            task.setCompletedAt(LocalDateTime.now());
            taskStore.put(taskId, task);

            log.info("[{}] Pipeline complete: {} bytes, ~{}s", taskId, finalAudio.length, duration);

        } catch (Exception e) {
            log.error("[{}] Pipeline failed", taskId, e);
            task.setStatus(GenerationTask.Status.FAILED);
            task.setErrorMessage(e.getMessage());
            taskStore.put(taskId, task);
        }
    }

    /**
     * 查询任务状态
     * 包含超时检查：如果任务处于处理中状态且超过 TASK_TIMEOUT_MINUTES 分钟，标记为 FAILED
     */
    public GenerationTask getTask(String taskId) {
        GenerationTask task = taskStore.get(taskId);
        if (task == null) return null;

        // 超时检查：如果任务处于处理中状态且超过超时时间，标记为 FAILED
        if (isProcessingStatus(task.getStatus()) && task.getCreatedAt() != null) {
            LocalDateTime timeoutThreshold = task.getCreatedAt().plusMinutes(TASK_TIMEOUT_MINUTES);
            if (LocalDateTime.now().isAfter(timeoutThreshold)) {
                log.warn("[{}] Task timed out (status={}, createdAt={})", taskId, task.getStatus(), task.getCreatedAt());
                task.setStatus(GenerationTask.Status.FAILED);
                task.setErrorMessage("Task timed out after " + TASK_TIMEOUT_MINUTES + " minutes");
                taskStore.put(taskId, task);
            }
        }

        return task;
    }

    /**
     * 判断任务是否处于处理中状态
     */
    private boolean isProcessingStatus(GenerationTask.Status status) {
        return status == GenerationTask.Status.GENERATING_SCRIPT
                || status == GenerationTask.Status.SYNTHESIZING_AUDIO
                || status == GenerationTask.Status.CONCATENATING;
    }

    /**
     * 生成 AI 合规水印音频
     * 使用 TTS 合成一段简短的 AI 声明，拼接到音频开头。
     *
     * @param ttsProvider TTS 供应商
     * @param language 语言代码
     * @return 水印音频字节（MP3），失败返回 null
     */
    private byte[] generateWatermark(TTSProvider ttsProvider, String language) {
        String watermarkText = switch (language.toLowerCase()) {
            case "zh" -> "本音频由 Sky Voice AI 生成。";
            case "ja" -> "この音声は Sky Voice AI によって生成されました。";
            default -> "This audio was generated by AI using Sky Voice.";
        };

        try {
            // 使用主持人A的声音生成水印
            String voiceId = ttsProvider.getVoiceId(language, "A", null, null);
            return ttsProvider.synthesize(watermarkText, voiceId, language);
        } catch (Exception e) {
            log.warn("Watermark generation failed, skipping: {}", e.getMessage());
            return null;
        }
    }
}
