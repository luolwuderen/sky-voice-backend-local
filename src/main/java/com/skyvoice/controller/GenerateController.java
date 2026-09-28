package com.skyvoice.controller;

import com.skyvoice.dto.GenerateRequest;
import com.skyvoice.dto.GenerateResponse;
import com.skyvoice.dto.TaskStatusResponse;
import com.skyvoice.model.GenerationTask;
import com.skyvoice.service.PipelineService;
import com.skyvoice.service.UsageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Path;
import java.time.Duration;
import java.time.YearMonth;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class GenerateController {

    private static final Logger log = LoggerFactory.getLogger(GenerateController.class);

    private final PipelineService pipelineService;
    private final UsageService usageService;

    @Value("${sky-voice.audio.output-dir}")
    private String outputDir;

    @Value("${sky-voice.limits.free-monthly:3}")
    private int freeMonthlyLimit;

    // 用量统计：key = "yyyy-MM:userId"，value = 当月已用次数
    // MVP阶段用内存存储，重启清零；上线后换数据库持久化
    private final ConcurrentHashMap<String, AtomicInteger> monthlyUsage = new ConcurrentHashMap<>();

    /**
     * 提交生成任务
     */
    @PostMapping("/generate")
    public ResponseEntity<GenerateResponse> generate(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Plan", required = false, defaultValue = "FREE") String plan,
            @Valid @RequestBody GenerateRequest request) {

        // 用量校验：Pro用户50次/月，Free用户3次/月
        boolean isPro = !"FREE".equalsIgnoreCase(plan);

        if (usageService.isLimitExceeded(userId, isPro)) {
            int limit = isPro ? 50 : 3;
            return ResponseEntity.status(403).body(
                    GenerateResponse.builder()
                            .message("Monthly limit reached (" + limit + " generations). Upgrade to Pro for more.")
                            .build());
        }

        // 获取输入文本
        String inputText = switch (request.getInputType().toUpperCase()) {
            case "TEXT" -> request.getContent();
            case "URL" -> fetchUrlContent(request.getUrl());
            case "PDF" -> ""; // TODO: 实现PDF解析
            default -> throw new IllegalArgumentException("Invalid input type: " + request.getInputType());
        };

        if (inputText == null || inputText.isBlank()) {
            return ResponseEntity.badRequest().body(
                    GenerateResponse.builder().message("Input content is empty").build());
        }

        int targetMinutes = request.getTargetMinutes() != null ? request.getTargetMinutes() : 5;

        // 创建任务并异步执行（voiceA/voiceB 为用户请求级音色覆盖，空=走默认映射）
        GenerationTask task = pipelineService.createTask(userId, request.getInputType(), request.getLanguage());
        pipelineService.executePipeline(task.getTaskId(), inputText, request.getLanguage(), targetMinutes,
                request.getVoiceA(), request.getVoiceB(), isPro);

        // 用量计数（任务提交成功后才计数）
        usageService.incrementUsage(userId);

        return ResponseEntity.ok(GenerateResponse.builder()
                .taskId(task.getTaskId())
                .status("QUEUED")
                .message("Task created, processing...")
                .build());
    }

    /**
     * 查询任务状态
     */
    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<TaskStatusResponse> getTaskStatus(@PathVariable String taskId) {
        GenerationTask task = pipelineService.getTask(taskId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(TaskStatusResponse.builder()
                .taskId(task.getTaskId())
                .status(task.getStatus().name())
                .audioUrl(task.getAudioUrl())
                .durationSeconds(task.getDurationSeconds())
                .errorMessage(task.getErrorMessage())
                .progress(task.getProgress())
                .build());
    }

    /**
     * 下载生成的音频
     */
    @GetMapping("/audio/{filename}")
    public ResponseEntity<Resource> downloadAudio(@PathVariable String filename) {
        Path filePath = Path.of(outputDir, filename);
        Resource resource = new FileSystemResource(filePath);

        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    /**
     * 抓取URL内容，提取HTML中的文本
     * 简单实现：用WebClient发送GET请求，去除HTML标签
     */
    private String fetchUrlContent(String url) {
        try {
            WebClient client = WebClient.builder()
                    .baseUrl(url)
                    .build();

            String html = client.get()
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();

            if (html == null || html.isBlank()) {
                log.warn("URL fetch returned empty content: {}", url);
                return "";
            }

            // 简单HTML文本提取：去除标签、script/style内容、HTML实体
            String text = html;
            // 移除 script 和 style 标签及其内容
            text = text.replaceAll("(?is)<script[^>]*>.*?</script>", "");
            text = text.replaceAll("(?is)<style[^>]*>.*?</style>", "");
            // 移除 HTML 注释
            text = text.replaceAll("(?is)<!--.*?-->", "");
            // 移除所有 HTML 标签
            text = text.replaceAll("(?s)<[^>]+>", " ");
            // 解码常见 HTML 实体
            text = text.replace("&nbsp;", " ")
                       .replace("&amp;", "&")
                       .replace("&lt;", "<")
                       .replace("&gt;", ">")
                       .replace("&quot;", "\"")
                       .replace("&#39;", "'")
                       .replace("&apos;", "'");
            // 压缩空白
            text = text.replaceAll("\\s+", " ").trim();

            log.info("URL fetch success: {} -> {} chars", url, text.length());
            return text;

        } catch (Exception e) {
            log.error("URL fetch failed: {}", url, e);
            return "";
        }
    }
}
