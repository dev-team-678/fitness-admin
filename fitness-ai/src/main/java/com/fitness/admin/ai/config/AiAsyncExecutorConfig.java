package com.fitness.admin.ai.config;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * AI 异步线程池配置(Spring 化,统一管理,避免 static Executors 导致的资源泄露)。
 *
 * <p>暴露两个 Bean:
 * <ul>
 *   <li>{@code aiChatAsyncExecutor}: 小程序对话异步处理,类型 {@link AsyncTaskExecutor}</li>
 *   <li>{@code aiCallGuardExecutor}: LLM 调用超时包装(AiTimeoutGuard 用),类型 {@link AsyncTaskExecutor}</li>
 * </ul>
 *
 * <p>并发上限来自 {@link AiConfig#getConcurrencyLimit()},可被 Nacos 热刷新;
 * 关闭钩子通过 {@link PreDestroy} 保证 Tomcat 关停时线程池优雅退出。
 */
@Slf4j
@Configuration
public class AiAsyncExecutorConfig {

    private ThreadPoolTaskExecutor chatExecutor;
    private ThreadPoolTaskExecutor guardExecutor;

    @Bean(name = "aiChatAsyncExecutor")
    public AsyncTaskExecutor aiChatAsyncExecutor(AiConfig aiConfig) {
        int coreSize = resolveConcurrencyLimit(aiConfig);
        this.chatExecutor = buildExecutor("ai-chat-async", coreSize, coreSize * 2, 200);
        log.info("构建 aiChatAsyncExecutor: core={}, max={}, queue=200", coreSize, coreSize * 2);
        return this.chatExecutor;
    }

    @Bean(name = "aiCallGuardExecutor")
    public AsyncTaskExecutor aiCallGuardExecutor(AiConfig aiConfig) {
        int coreSize = resolveConcurrencyLimit(aiConfig);
        this.guardExecutor = buildExecutor("ai-call-guard", coreSize, coreSize * 2, 100);
        log.info("构建 aiCallGuardExecutor: core={}, max={}, queue=100", coreSize, coreSize * 2);
        return this.guardExecutor;
    }

    private static int resolveConcurrencyLimit(AiConfig aiConfig) {
        Integer v = aiConfig.getConcurrencyLimit();
        int cpu = Math.max(2, Runtime.getRuntime().availableProcessors());
        if (v == null || v <= 0) {
            return cpu;
        }
        // 防止 Nacos 配成 1 导致完全无并发,也防止过大拖垮 LLM 上游
        return Math.max(2, Math.min(v, cpu * 4));
    }

    private static ThreadPoolTaskExecutor buildExecutor(String threadNamePrefix,
                                                        int coreSize, int maxSize, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(threadNamePrefix + "-");
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(60);
        executor.setDaemon(true);
        // 拒绝策略:CallerRuns — Tomcat 主线程兜底,避免任务被静默丢弃
        executor.setRejectedExecutionHandler(
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        // 透传请求上下文(子线程能取到 RequestAttributes,便于日志关联)
        executor.setTaskDecorator(mdcTaskDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * 把当前线程的 RequestAttributes / MDC 透传到子线程,
     * 保证异步日志里还能看到 traceId / userId。
     */
    private static TaskDecorator mdcTaskDecorator() {
        return runnable -> {
            RequestAttributes ctx = RequestContextHolder.getRequestAttributes();
            java.util.Map<String, String> mdcSnapshot = org.slf4j.MDC.getCopyOfContextMap();
            return () -> {
                if (ctx != null) {
                    RequestContextHolder.setRequestAttributes(ctx);
                }
                java.util.Map<String, String> previousMdc = null;
                if (mdcSnapshot != null) {
                    previousMdc = org.slf4j.MDC.getCopyOfContextMap();
                    org.slf4j.MDC.setContextMap(mdcSnapshot);
                }
                try {
                    runnable.run();
                } finally {
                    RequestContextHolder.resetRequestAttributes();
                    if (mdcSnapshot != null) {
                        if (previousMdc != null) {
                            org.slf4j.MDC.setContextMap(previousMdc);
                        } else {
                            org.slf4j.MDC.clear();
                        }
                    }
                }
            };
        };
    }

    @PreDestroy
    public void shutdown() {
        shutdownQuietly(chatExecutor, "aiChatAsyncExecutor");
        shutdownQuietly(guardExecutor, "aiCallGuardExecutor");
    }

    private static void shutdownQuietly(ThreadPoolTaskExecutor executor, String name) {
        if (executor == null) return;
        try {
            log.info("关闭 AI 异步线程池: {}", name);
            executor.shutdown();
            if (!executor.getThreadPoolExecutor().awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.getThreadPoolExecutor().shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.getThreadPoolExecutor().shutdownNow();
        } catch (Exception e) {
            log.warn("关闭线程池异常: {}", name, e);
        }
    }
}
