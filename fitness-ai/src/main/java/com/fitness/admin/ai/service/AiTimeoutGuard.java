package com.fitness.admin.ai.service;

import com.fitness.admin.ai.config.AiConfig;
import com.fitness.admin.common.enums.ResultCodeEnum;
import com.fitness.admin.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * AI 调用超时控制器。
 *
 * <p>统一从 {@link AiConfig#getTimeoutSeconds()} 读取阈值,
 * 包装任意 {@link Callable} 并在超时时抛出 {@link BizException}({@code LLM_TIMEOUT})。
 * 避免每个调用点都手写 {@code Future.get(timeout)}。
 *
 * <p>底层执行器由 {@link com.fitness.admin.ai.config.AiAsyncExecutorConfig#aiCallGuardExecutor}
 * 提供(Spring 管理,支持热修改 corePoolSize / 容器关停时 graceful shutdown)。
 */
@Slf4j
@Service
public class AiTimeoutGuard {

    private final AiConfig aiConfig;
    private final Executor executor;

    public AiTimeoutGuard(AiConfig aiConfig,
                          @Qualifier("aiCallGuardExecutor") Executor executor) {
        this.aiConfig = aiConfig;
        this.executor = executor;
    }

    public <T> T call(Callable<T> task) {
        int timeout = aiConfig.getTimeoutSeconds() != null ? aiConfig.getTimeoutSeconds() : 60;
        return callWithTimeout(timeout, task);
    }

    /**
     * 与 {@link #call(Callable)} 行为一致,但超时阈值由调用方指定。
     * 用于异步场景:前端响应时间较短,可设置较短阈值;后台真正调用可放宽到 90s+。
     */
    public <T> T callWithTimeout(int timeoutSeconds, Callable<T> task) {
        Future<T> future;
        if (executor instanceof java.util.concurrent.ExecutorService es) {
            future = es.submit(task);
        } else {
            // 兜底:Executor 接口不支持 submit,转用 common pool(理论上不会走到这里)
            java.util.concurrent.ExecutorService fallback =
                    java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "ai-call-guard-fallback");
                        t.setDaemon(true);
                        return t;
                    });
            try {
                future = fallback.submit(task);
            } finally {
                fallback.shutdown();
            }
        }
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("AI 调用超时 (>{}s)", timeoutSeconds);
            throw new BizException(ResultCodeEnum.LLM_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ResultCodeEnum.AI_SERVICE_ERROR);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("AI 调用失败", cause);
            if (cause instanceof BizException be) {
                throw be;
            }
            throw new BizException(ResultCodeEnum.AI_UPSTREAM_ERROR);
        }
    }
}
