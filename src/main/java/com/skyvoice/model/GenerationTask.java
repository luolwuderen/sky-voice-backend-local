package com.skyvoice.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 音频生成任务
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerationTask {

    private String taskId;
    private String userId;
    private Status status;
    private String inputType;   // TEXT, URL, PDF
    private String language;     // zh, en, ja
    private String audioUrl;
    private int durationSeconds;
    private String errorMessage;
    private String progress;  // 进度，如 "3/10" 表示3/10段完成
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    public enum Status {
        QUEUED, GENERATING_SCRIPT, SYNTHESIZING_AUDIO, CONCATENATING, DONE, FAILED
    }

    public static GenerationTask create(String userId, String inputType, String language) {
        return GenerationTask.builder()
                .taskId(UUID.randomUUID().toString())
                .userId(userId)
                .status(Status.QUEUED)
                .inputType(inputType)
                .language(language)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
