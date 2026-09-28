package com.skyvoice.service;

import com.skyvoice.config.AppConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用量统计服务 — 共享给 GenerateController 和 UsageController
 *
 * MVP 阶段使用内存存储，重启清零。
 * 上线后替换为数据库持久化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageService {

    private final AppConfig appConfig;

    // key = "yyyy-MM:userId"，value = 当月已用次数
    private final ConcurrentHashMap<String, AtomicInteger> monthlyUsage = new ConcurrentHashMap<>();

    /**
     * 获取用户当月已用次数
     */
    public int getUsedCount(String userId) {
        String key = YearMonth.now().toString() + ":" + userId;
        return monthlyUsage.getOrDefault(key, new AtomicInteger(0)).get();
    }

    /**
     * 递增用户用量
     */
    public void incrementUsage(String userId) {
        String key = YearMonth.now().toString() + ":" + userId;
        monthlyUsage.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
    }

    /**
     * 检查是否超过限制
     * @param userId 用户ID
     * @param isPro 是否Pro用户
     * @return true=已超限，false=未超限
     */
    public boolean isLimitExceeded(String userId, boolean isPro) {
        int used = getUsedCount(userId);
        int limit = isPro
                ? appConfig.getLimits().getProMonthly()
                : appConfig.getLimits().getFreeMonthly();
        return used >= limit;
    }

    /**
     * 获取用户剩余次数
     */
    public int getRemaining(String userId, boolean isPro) {
        int used = getUsedCount(userId);
        int limit = isPro
                ? appConfig.getLimits().getProMonthly()
                : appConfig.getLimits().getFreeMonthly();
        return Math.max(0, limit - used);
    }
}
