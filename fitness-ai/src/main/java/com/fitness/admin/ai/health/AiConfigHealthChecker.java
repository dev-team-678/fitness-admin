package com.fitness.admin.ai.health;

import com.fitness.admin.ai.config.AiConfig;
import com.fitness.admin.ai.rag.EmbeddingService;
import com.fitness.admin.ai.security.ApiKeyManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * AI 配置健康检查器 (2026-06-20 P1-7)。
 *
 * <p>触发时机:
 * <ol>
 *   <li>应用启动完成 (ApplicationReadyEvent) — 启动后立即自检一次,提前发现问题</li>
 *   <li>Nacos 配置变更 (EnvironmentChangeEvent) — RefreshScope 重建后自检,确认新 key 有效</li>
 * </ol>
 *
 * <p>自检策略:
 * <ul>
 *   <li>Embedding API — 用一句话调用 embed("health-check")</li>
 *   <li>失败时:ERROR 日志告警(后续可接入飞书 / 邮件 / 钉钉)</li>
 *   <li>成功时:INFO 一行通过</li>
 * </ul>
 *
 * <p>注意:自检失败不影响应用启动 — 业务调用时仍会触发 ApiKeyManager 熔断切换。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiConfigHealthChecker {

    private static final String HEALTH_CHECK_PROBE = "health-check-probe";

    private final AiConfig aiConfig;
    private final EmbeddingService embeddingService;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        check("startup");
    }

    /**
     * Nacos 配置变更后会触发 EnvironmentChangeEvent,
     * RefreshScope 重建 AiConfig 后,我们再 ping 一次确认新 key 可用。
     */
    @EventListener(EnvironmentChangeEvent.class)
    public void onEnvironmentChange(EnvironmentChangeEvent event) {
        boolean aiKeyChanged = event.getKeys().stream()
                .anyMatch(k -> k.startsWith("ai."));
        if (!aiKeyChanged) return;
        log.info("检测到 ai.* 配置变更,触发自检");
        check("refresh");
    }

    private void check(String trigger) {
        try {
            embeddingService.embed(HEALTH_CHECK_PROBE);
            log.info("AI Embedding 自检通过 [{}] url={}", trigger,
                    aiConfig.getEffectiveEmbeddingApiBaseUrl());
        } catch (ApiKeyManager.AllKeysExhaustedException e) {
            log.error("AI Embedding 自检失败 [{}]: 所有 key 均不可用: {}",
                    trigger, e.getMessage());
        } catch (Exception e) {
            log.error("AI Embedding 自检失败 [{}]: {} (建议立即检查 Nacos 中 ai.embeddingApiKey 是否正确)",
                    trigger, e.getMessage());
        }
    }
}