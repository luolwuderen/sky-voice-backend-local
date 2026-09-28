package com.skyvoice.controller;

import com.skyvoice.service.UsageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用量查询 Controller
 *
 * 返回当前用户本月用量和配额，供前端展示。
 * GET /api/v1/usage
 * Header: X-User-Id, X-User-Plan (可选，默认 FREE)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/usage")
@RequiredArgsConstructor
public class UsageController {

    private final UsageService usageService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getUsage(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Plan", required = false, defaultValue = "FREE") String plan) {

        boolean isPro = !"FREE".equalsIgnoreCase(plan);
        int used = usageService.getUsedCount(userId);
        int limit = isPro ? 50 : 3;
        int remaining = usageService.getRemaining(userId, isPro);

        return ResponseEntity.ok(Map.of(
                "used", used,
                "limit", limit,
                "remaining", remaining,
                "plan", isPro ? "PRO" : "FREE"
        ));
    }
}
