package com.skyvoice.controller;

import com.skyvoice.config.AppConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IAP 收据验证 Controller
 *
 * 接收前端发来的 Apple 收据，向 Apple 验证服务器确认购买有效性。
 * 验证通过后更新用户订阅状态。
 *
 * Apple 验证文档: https://developer.apple.com/documentation/appstorereceipts/verifyreceipt
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/iap")
@RequiredArgsConstructor
public class IapVerifyController {

    private final AppConfig appConfig;

    // Apple 验证端点
    private static final String PRODUCTION_URL = "https://buy.itunes.apple.com/verifyReceipt";
    private static final String SANDBOX_URL = "https://sandbox.itunes.apple.com/verifyReceipt";

    // 用户订阅状态（MVP 内存存储，上线换数据库）
    // key: userId, value: SubscriptionInfo
    private final ConcurrentHashMap<String, SubscriptionInfo> subscriptions = new ConcurrentHashMap<>();

    /**
     * 验证收据
     *
     * POST /api/v1/iap/verify
     * Header: X-User-Id
     * Body: { "receiptData": "...", "productId": "sky_voice_pro_monthly" }
     */
    @PostMapping("/verify")
    public Mono<ResponseEntity<Map<String, Object>>> verifyReceipt(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody Map<String, String> body) {

        String receiptData = body.get("receiptData");
        String productId = body.get("productId");

        if (receiptData == null || receiptData.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Receipt data is required"
            )));
        }

        // 先用生产环境验证，状态码 21007 表示是沙盒收据，需要转沙盒验证
        return verifyWithApple(PRODUCTION_URL, receiptData)
                .flatMap(result -> {
                    Object statusObj = result.get("status");
                    int statusVal = statusObj instanceof Number ? ((Number) statusObj).intValue() : -1;
                    if (statusVal == 21007) {
                        // 沙盒收据，转沙盒验证
                        log.info("Sandbox receipt detected, retrying with sandbox URL");
                        return verifyWithApple(SANDBOX_URL, receiptData);
                    }
                    return Mono.just(result);
                })
                .map(result -> {
                    Object statusObj = result.get("status");
                    int status = statusObj instanceof Number ? ((Number) statusObj).intValue() : -1;
                    if (status == 0) {
                        // 验证成功，更新用户订阅
                        SubscriptionInfo info = new SubscriptionInfo();
                        info.userId = userId;
                        info.productId = productId;
                        info.plan = productId.contains("yearly") ? "PRO_YEARLY" : "PRO_MONTHLY";
                        info.verifiedAt = LocalDateTime.now();
                        info.expiresAt = info.verifiedAt.plus(
                                productId.contains("yearly") ? 365 : 30, java.time.temporal.ChronoUnit.DAYS
                        );
                        subscriptions.put(userId, info);
                        log.info("IAP verified successfully: userId={}, product={}", userId, productId);

                        return ResponseEntity.ok(Map.of(
                                "success", true,
                                "plan", info.plan,
                                "expiresAt", info.expiresAt.toString()
                        ));
                    } else {
                        log.warn("IAP verification failed: userId={}, status={}", userId, status);
                        return ResponseEntity.ok(Map.of(
                                "success", false,
                                "message", "Receipt verification failed (status: " + status + ")"
                        ));
                    }
                });
    }

    /**
     * 查询用户订阅状态
     * GET /api/v1/iap/status
     * Header: X-User-Id
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getSubscriptionStatus(
            @RequestHeader("X-User-Id") String userId) {

        SubscriptionInfo info = subscriptions.get(userId);
        if (info == null) {
            return ResponseEntity.ok(Map.of(
                    "plan", "FREE",
                    "isActive", false
            ));
        }

        boolean isActive = info.expiresAt.isAfter(LocalDateTime.now());
        return ResponseEntity.ok(Map.of(
                "plan", info.plan,
                "isActive", isActive,
                "expiresAt", info.expiresAt.toString()
        ));
    }

    /**
     * 向 Apple 验证服务器发送请求
     */
    @SuppressWarnings("unchecked")
    private Mono<Map<String, Object>> verifyWithApple(String url, String receiptData) {
        String sharedSecret = appConfig.getIap() != null
                ? appConfig.getIap().getSharedSecret()
                : null;

        java.util.Map<String, Object> requestBody = new java.util.HashMap<>();
        requestBody.put("receipt-data", receiptData);
        requestBody.put("exclude-old-transactions", true);
        if (sharedSecret != null && !sharedSecret.isBlank()) {
            requestBody.put("password", sharedSecret);
        }

        return WebClient.builder()
                .baseUrl(url)
                .build()
                .post()
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(java.time.Duration.ofSeconds(15))
                .map(response -> (Map<String, Object>) response);
    }

    /**
     * 订阅信息（内部类）
     * 上线后替换为数据库实体
     */
    private static class SubscriptionInfo {
        String userId;
        String productId;
        String plan;
        LocalDateTime verifiedAt;
        LocalDateTime expiresAt;
    }
}
