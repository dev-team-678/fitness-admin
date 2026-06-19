package com.fitness.admin.ai.service;

import com.fitness.admin.ai.config.AiConfig;
import com.fitness.admin.common.enums.ResultCodeEnum;
import com.fitness.admin.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * AI 配额守护(dailyChatLimit / dailyPlanLimit / maxTokensPerDay)。
 *
 * <p>使用 Redis 计数器,key:
 * <ul>
 *   <li>ai:daily:chat:{userId}:{yyyyMMdd}  - 当日对话计数</li>
 *   <li>ai:daily:plan:{userId}:{yyyyMMdd}  - 当日计划生成计数</li>
 *   <li>ai:tokens:global:{yyyyMMdd}        - 当日全局 token 用量</li>
 * </ul>
 *
 * <p>Redis 不可用时降级为不限制(与 {@link AiRateLimiter} 一致)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiQuotaGuard {

    private static final String DAILY_CHAT_PREFIX = "ai:daily:chat:";
    private static final String DAILY_PLAN_PREFIX = "ai:daily:plan:";
    private static final String TOKENS_GLOBAL_PREFIX = "ai:tokens:global:";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate stringRedisTemplate;
    private final AiConfig aiConfig;

    /**
     * 进入 chat 入口前调用。超限抛 {@link BizException}。
     */
    public void checkChatQuota(Long userId) {
        Integer limit = aiConfig.getDailyChatLimit();
        if (limit == null || limit <= 0) return;
        String key = DAILY_CHAT_PREFIX + userId + ":" + today();
        Long current = incrementWithTtl(key);
        if (current != null && current > limit) {
            log.warn("AI 每日对话超限: userId={}, count={}, limit={}", userId, current, limit);
            throw new BizException(ResultCodeEnum.AI_RATE_LIMIT);
        }
    }

    /**
     * 进入 plan/generate 入口前调用。超限抛 {@link BizException}。
     */
    public void checkPlanQuota(Long userId) {
        Integer limit = aiConfig.getDailyPlanLimit();
        if (limit == null || limit <= 0) return;
        String key = DAILY_PLAN_PREFIX + userId + ":" + today();
        Long current = incrementWithTtl(key);
        if (current != null && current > limit) {
            log.warn("AI 每日计划生成超限: userId={}, count={}, limit={}", userId, current, limit);
            throw new BizException(ResultCodeEnum.AI_RATE_LIMIT);
        }
    }

    /**
     * AI 调用结束后把真实 token 用量累加到全局配额。超限抛 {@link BizException}。
     */
    public void checkAndAccumulateTokens(int usedTokens) {
        Long limit = aiConfig.getMaxTokensPerDay();
        if (limit == null || limit <= 0) return;
        String key = TOKENS_GLOBAL_PREFIX + today();
        try {
            Long current = stringRedisTemplate.opsForValue().increment(key, usedTokens);
            if (current != null && current == usedTokens) {
                stringRedisTemplate.expire(key, Duration.ofDays(2));
            }
            if (current != null && current > limit) {
                log.warn("AI 全局 token 用量超限: current={}, limit={}", current, limit);
                throw new BizException(ResultCodeEnum.AI_RATE_LIMIT);
            }
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 不可用,token 配额降级为不限制: err={}", e.getMessage());
        }
    }

    private Long incrementWithTtl(String key) {
        try {
            Long current = stringRedisTemplate.opsForValue().increment(key);
            if (current != null && current == 1L) {
                stringRedisTemplate.expire(key, Duration.ofDays(2));
            }
            return current;
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 不可用,AI 配额降级为不限制: key={}, err={}", key, e.getMessage());
            return null;
        }
    }

    private String today() {
        return LocalDate.now().format(DATE_FMT);
    }
}
