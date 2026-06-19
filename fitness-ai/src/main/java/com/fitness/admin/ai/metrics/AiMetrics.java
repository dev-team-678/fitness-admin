package com.fitness.admin.ai.metrics;

import com.fitness.admin.ai.config.AiConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * AI 模块 Micrometer 指标:
 * <ul>
 *   <li>{@code ai.llm.calls}: Counter,按 provider 拆分,每成功一次 +1</li>
 *   <li>{@code ai.llm.failures}: Counter,按 provider 拆分</li>
 *   <li>{@code ai.llm.latency}: Timer,记录每次 LLM 调用的延迟</li>
 *   <li>{@code ai.llm.tokens}: Counter,按 provider + type(prompt/completion/total) 拆分</li>
 *   <li>{@code ai.queue.size}: Gauge,异步线程池队列长度</li>
 *   <li>{@code ai.queue.active}: Gauge,正在执行的线程数</li>
 * </ul>
 *
 * <p>Micrometer 自动适配 Spring Boot Actuator,指标可通过 /actuator/prometheus 暴露。
 */
@Component
public class AiMetrics {

    private final MeterRegistry registry;
    private final AiConfig aiConfig;
    private final org.springframework.core.task.AsyncTaskExecutor chatExecutor;

    public AiMetrics(MeterRegistry registry,
                     AiConfig aiConfig,
                     @Qualifier("aiChatAsyncExecutor") org.springframework.core.task.AsyncTaskExecutor chatExecutor) {
        this.registry = registry;
        this.aiConfig = aiConfig;
        this.chatExecutor = chatExecutor;
    }

    /**
     * 注册 Gauge(必须在构造时注册,之后 Micrometer 周期性回调)。
     */
    @jakarta.annotation.PostConstruct
    public void registerGauges() {
        if (chatExecutor instanceof ThreadPoolTaskExecutor tp) {
            registry.gauge("ai.queue.size",
                    Tags.of(Tag.of("pool", "aiChat")),
                    tp.getThreadPoolExecutor(),
                    e -> e == null ? 0 : e.getQueue().size());
            registry.gauge("ai.queue.active",
                    Tags.of(Tag.of("pool", "aiChat")),
                    tp.getThreadPoolExecutor(),
                    e -> e == null ? 0 : e.getActiveCount());
        }
    }

    public void recordCall(String provider, int promptTokens, int completionTokens,
                           long latencyMs, boolean success) {
        Tags tags = Tags.of(Tag.of("provider", provider == null ? "unknown" : provider));
        Counter.builder("ai.llm.calls").tags(tags).register(registry).increment();
        if (!success) {
            Counter.builder("ai.llm.failures").tags(tags).register(registry).increment();
            return;
        }
        Timer.builder("ai.llm.latency")
                .tags(tags)
                .publishPercentiles(0.5, 0.9, 0.99)
                .register(registry)
                .record(latencyMs, TimeUnit.MILLISECONDS);

        if (promptTokens > 0) {
            Counter.builder("ai.llm.tokens")
                    .tags(tags.and("type", "prompt"))
                    .register(registry)
                    .increment(promptTokens);
        }
        if (completionTokens > 0) {
            Counter.builder("ai.llm.tokens")
                    .tags(tags.and("type", "completion"))
                    .register(registry)
                    .increment(completionTokens);
        }
        int total = promptTokens + completionTokens;
        if (total > 0) {
            Counter.builder("ai.llm.tokens")
                    .tags(tags.and("type", "total"))
                    .register(registry)
                    .increment(total);
        }
    }
}