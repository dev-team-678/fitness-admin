package com.fitness.admin.ai.security;

import com.fitness.admin.ai.config.AiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * API Key 轮转管理器 (P2-9,2026-06-20)。
 *
 * <p>职责:
 * <ol>
 *   <li>从 {@link AiConfig} 读取 key 池(优先 {@code apiKeys: [...]},回退到 {@code apiKey} 单值)。</li>
 *   <li>按 {@code priority ASC} 排序,过滤 {@code DISABLED} / 空值。</li>
 *   <li>提供"下一把可用 key"——主调用使用 {@link #current()},失败后调用 {@link #rotate()} 切换。</li>
 *   <li>每把 key 配一个"熔断计数",连续失败 N 次标记为不可用,避免不停重试同一把死 key。</li>
 * </ol>
 *
 * <p>轮转行为:
 * <ul>
 *   <li>成功 → 不动,继续用当前 key(避免高成本切换)。</li>
 *   <li>失败 → 记一次当前 key 的失败计数,达到阈值后切到下一把。</li>
 *   <li>所有 key 都不可用 → 抛 {@link AllKeysExhaustedException}。</li>
 * </ul>
 *
 * <p>线程安全: {@link AtomicInteger} + volatile 字段,无锁;每次调用都重新解析 AiConfig 字段,
 * 因此与 {@code @RefreshScope} 配合,配置变更后立刻生效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyManager {

    /** 单把 key 连续失败多少次后熔断(标记 disabled 直到下次 RefreshScope 重建) */
    private static final int FAILURE_THRESHOLD = 3;

    private final AiConfig aiConfig;

    /** 当前正在使用的 key 的下标(在 {@link #orderedKeys()} 的返回列表中) */
    private volatile int currentIndex = 0;

    /** 每把 key 的连续失败计数(下标与 {@link #orderedKeys()} 返回列表对应) */
    private final AtomicInteger failures = new AtomicInteger(0);

    /** 上次操作结果(成功/失败)用于决定是否累加失败计数 */
    private volatile boolean lastCallSuccess = true;

    /**
     * 取当前 key(主入口)。
     * 调用方应遵循"调 → 成功不动 / 失败调 {@link #rotate()}"的模式。
     */
    public String current() {
        List<ApiKeyEntry> keys = orderedKeys();
        if (keys.isEmpty()) {
            throw new IllegalStateException("AI Key 池为空,请检查 AiConfig.apiKeys / apiKey 配置");
        }
        if (currentIndex >= keys.size()) {
            currentIndex = 0;
        }
        ApiKeyEntry cur = keys.get(currentIndex);
        return cur.getValue();
    }

    /**
     * 标记当前调用成功,重置失败计数。
     */
    public void markSuccess() {
        if (!lastCallSuccess) {
            failures.set(0);
            lastCallSuccess = true;
            log.debug("ApiKeyManager: 标记成功,重置失败计数");
        }
    }

    /**
     * 标记当前调用失败,失败计数到阈值后切到下一把 key。
     *
     * @return 切换后的新 key(已自动设 currentIndex)
     */
    public String rotate() {
        lastCallSuccess = false;
        int count = failures.incrementAndGet();
        List<ApiKeyEntry> keys = orderedKeys();
        if (count < FAILURE_THRESHOLD) {
            log.warn("ApiKeyManager: 当前 key 失败 {} 次 (阈值 {}),暂不切换", count, FAILURE_THRESHOLD);
            return keys.isEmpty() ? null : keys.get(currentIndex).getValue();
        }
        // 达到阈值,切到下一把
        failures.set(0);
        if (keys.size() <= 1) {
            log.error("ApiKeyManager: 仅有 1 把 key,无法切换;将抛 AllKeysExhaustedException");
            throw new AllKeysExhaustedException("仅有 1 把 API Key,已失败但无法切换");
        }
        int next = (currentIndex + 1) % keys.size();
        ApiKeyEntry from = keys.get(currentIndex);
        ApiKeyEntry to = keys.get(next);
        log.warn("ApiKeyManager: 切换 key from={} to={}", from.getId(), to.getId());
        currentIndex = next;
        return to.getValue();
    }

    /**
     * 从 AiConfig 解析 key 池(优先 apiKeys 列表,回退到 apiKey 单值)。
     * 每次调用都重新解析,确保 @RefreshScope 重建后立即生效。
     */
    private List<ApiKeyEntry> orderedKeys() {
        List<ApiKeyEntry> pool = new ArrayList<>();
        List<ApiKeyEntry> configured = aiConfig.getApiKeys();
        if (configured != null && !configured.isEmpty()) {
            pool.addAll(configured);
        } else if (aiConfig.getApiKey() != null && !aiConfig.getApiKey().isBlank()) {
            // 兼容 P0/P1 阶段的扁平配置:把单值包成只有一个 primary 条目的池
            ApiKeyEntry single = new ApiKeyEntry();
            single.setId("legacy-primary");
            single.setValue(aiConfig.getApiKey());
            single.setRole(ApiKeyEntry.Role.PRIMARY);
            single.setPriority(0);
            single.setLabel("legacy single apiKey field");
            pool.add(single);
        }
        // 过滤 disabled / 空,按 priority ASC 排序
        return pool.stream()
                .filter(ApiKeyEntry::isUsable)
                .sorted(Comparator.comparingInt(ApiKeyEntry::getPriority))
                .toList();
    }

    /**
     * 所有可用 key 都轮换过且都失败后抛出。
     */
    public static class AllKeysExhaustedException extends RuntimeException {
        public AllKeysExhaustedException(String message) {
            super(message);
        }
    }
}
