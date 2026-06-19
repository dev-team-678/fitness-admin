package com.fitness.admin.ai.service;

import com.fitness.admin.ai.config.AiConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 统一 OkHttp 客户端配置。
 *
 * <p>所有调用 LLM / Embedding 的 OkHttpClient 都从这里出,
 * 超时由 {@link AiConfig#getTimeoutSeconds()} 派生:
 * <ul>
 *   <li>connectTimeout = 30s(与 LLM 鉴权独立)</li>
 *   <li>readTimeout    = ai.timeoutSeconds + 10s 缓冲,避免 Future.get 抢先抛</li>
 *   <li>writeTimeout   = 30s</li>
 * </ul>
 *
 * <p>线程池统一为 32 线程, 避免对上游 LLM 端口过载。
 */
@Slf4j
@Configuration
public class AiHttpClientConfig {

    @Bean
    public OkHttpClient aiHttpClient(AiConfig aiConfig) {
        int readSec = (aiConfig.getTimeoutSeconds() != null ? aiConfig.getTimeoutSeconds() : 60) + 10;
        log.info("构建 AI OkHttpClient: readTimeout={}s (来自 ai.timeoutSeconds={})",
                readSec, aiConfig.getTimeoutSeconds());
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(readSec, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }
}
