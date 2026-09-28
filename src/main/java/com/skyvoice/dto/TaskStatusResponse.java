package com.skyvoice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskStatusResponse {
    private String taskId;
    private String status;
    private String audioUrl;
    private Integer durationSeconds;
    private String errorMessage;
    private String progress;
}
