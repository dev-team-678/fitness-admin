package com.fitness.admin.ai.security;

import com.fitness.admin.ai.config.AiConfig;
import com.fitness.admin.ai.security.ApiKeyEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Embedding API Key 轮转管理器 (P2-9,2026-06-20)。
 *
 * <p>独立于 {@link ApiKeyManager}:Embedding 的 key 池与 chat 的 key 池分开管理
 * (常见场景:chat 走 OpenAI,Embedding 走 DashScope,key 来源完全不同)。
 *
 * <p>设计:
 * <ul>
 *   <li>优先读 {@code ai.embeddingApiKeys: [{...}]}</li>
 *   <li>回退到 {@code ai.embeddingApiKey} 单值</li>
 *   <li>如果 embeddingApiKey 为空,再回退到 chat key 池的 primary(可能不对,但保留兼容)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingApiKeyManager {

    /** Embedding key 失败阈值比 chat 更低(Embedding 是高频短调用,失败很快) */
    private static final int FAILURE_THRESHOLD = 3;

    private final AiConfig aiConfig;

    private volatile int currentIndex = 0;
    private final AtomicInteger failures = new AtomicInteger(0);
    private volatile boolean lastCallSuccess = true;

    public String current() {
        List<ApiKeyEntry> keys = orderedKeys();
        if (keys.isEmpty()) {
            throw new IllegalStateException(
                    "Embedding Key 池为空,请检查 AiConfig.embeddingApiKeys / embeddingApiKey 配置");
        }
        if (currentIndex >= keys.size()) {
            currentIndex = 0;
        }
        return keys.get(currentIndex).getValue();
    }

    public void markSuccess() {
        if (!lastCallSuccess) {
            failures.set(0);
            lastCallSuccess = true;
        }
    }

    public String rotate() {
        lastCallSuccess = false;
        int count = failures.incrementAndGet();
        List<ApiKeyEntry> keys = orderedKeys();
        if (count < FAILURE_THRESHOLD) {
            log.warn("EmbeddingApiKeyManager: 当前 key 失败 {} 次 (阈值 {}),暂不切换", count, FAILURE_THRESHOLD);
            return keys.isEmpty() ? null : keys.get(currentIndex).getValue();
        }
        failures.set(0);
        if (keys.size() <= 1) {
            log.error("EmbeddingApiKeyManager: 仅有 1 把 key,无法切换");
            throw new ApiKeyManager.AllKeysExhaustedException("仅有 1 把 Embedding Key,已失败但无法切换");
        }
        int next = (currentIndex + 1) % keys.size();
        log.warn("EmbeddingApiKeyManager: 切换 key from={} to={}",
                keys.get(currentIndex).getId(), keys.get(next).getId());
        currentIndex = next;
        return keys.get(next).getValue();
    }

    private List<ApiKeyEntry> orderedKeys() {
        List<ApiKeyEntry> pool = new ArrayList<>();
        List<ApiKeyEntry> configured = aiConfig.getEmbeddingApiKeys();
        if (configured != null && !configured.isEmpty()) {
            pool.addAll(configured);
        } else if (aiConfig.getEmbeddingApiKey() != null && !aiConfig.getEmbeddingApiKey().isBlank()) {
            ApiKeyEntry single = new ApiKeyEntry();
            single.setId("legacy-embedding-primary");
            single.setValue(aiConfig.getEmbeddingApiKey());
            single.setRole(ApiKeyEntry.Role.PRIMARY);
            single.setPriority(0);
            single.setLabel("legacy single embeddingApiKey field");
            pool.add(single);
        } else if (aiConfig.getApiKey() != null && !aiConfig.getApiKey().isBlank()) {
            // 极端兜底:embedding 没配 → 用 chat key(DashScope 不会认,通常会 401,但能跑通代码路径)
            log.warn("EmbeddingApiKeyManager: embeddingApiKey 为空,回退到 chat apiKey(通常会 401)");
            ApiKeyEntry single = new ApiKeyEntry();
            single.setId("fallback-chat-primary");
            single.setValue(aiConfig.getApiKey());
            single.setRole(ApiKeyEntry.Role.PRIMARY);
            single.setPriority(0);
            single.setLabel("fallback to chat apiKey");
            pool.add(single);
        }
        return pool.stream()
                .filter(ApiKeyEntry::isUsable)
                .sorted(Comparator.comparingInt(ApiKeyEntry::getPriority))
                .toList();
    }
}
