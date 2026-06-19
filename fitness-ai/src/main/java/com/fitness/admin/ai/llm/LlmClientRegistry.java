package com.fitness.admin.ai.llm;

import com.fitness.admin.ai.config.AiConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LlmClient 路由表。
 *
 * <p>启动时把所有 {@link LlmClient} Bean 按 {@link LlmClient#provider()} 注册进 map;
 * 调用方按 {@link AiConfig#getProvider()} 查表拿客户端。
 *
 * <p>支持 Nacos 热刷新:RefreshScope 重建 AiConfig 时,
 * {@link #resolveCurrent()} 会用最新 provider 重新选路由。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClientRegistry {

    private final List<LlmClient> clients;
    private final Map<String, LlmClient> byProvider = new HashMap<>();
    private final AtomicReference<LlmClient> current = new AtomicReference<>();

    @PostConstruct
    public void init() {
        for (LlmClient c : clients) {
            LlmClient prev = byProvider.put(c.provider().toLowerCase(), c);
            if (prev != null) {
                log.warn("LlmClient provider 重复注册: {} (旧:{},新:{})",
                        c.provider(), prev.getClass().getSimpleName(), c.getClass().getSimpleName());
            }
        }
        log.info("LlmClientRegistry 初始化: providers={}", byProvider.keySet());
    }

    /**
     * 拿当前 provider 对应的客户端。Provider 在 AiConfig 中可热改,这里用 AtomicReference 缓存,
     * 当 AiConfig 变化时 {@link #resolveCurrent(AiConfig)} 会自动重选。
     */
    public LlmClient current(AiConfig aiConfig) {
        LlmClient cached = current.get();
        String provider = normalize(aiConfig.getProvider());
        if (cached != null && provider.equals(cached.provider())) {
            return cached;
        }
        return resolveCurrent(aiConfig);
    }

    /**
     * 强制重选当前路由(供测试 / 切换 provider 后立即生效)。
     */
    public LlmClient resolveCurrent(AiConfig aiConfig) {
        String provider = normalize(aiConfig.getProvider());
        LlmClient selected = byProvider.get(provider);
        if (selected == null) {
            // 没找到 → 兜底 OpenAI(并打印日志,避免线上静默错误)
            log.warn("未找到 provider={} 对应的 LlmClient,fallback 到 openai", provider);
            selected = byProvider.get("openai");
        }
        if (selected == null) {
            throw new IllegalStateException("没有可用的 LlmClient: providers=" + byProvider.keySet());
        }
        current.set(selected);
        return selected;
    }

    private static String normalize(String p) {
        return p == null || p.isBlank() ? "openai" : p.toLowerCase();
    }
}
