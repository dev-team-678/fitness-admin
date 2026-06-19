package com.fitness.admin.ai.service;

import com.fitness.admin.ai.config.AiConfig;
import com.fitness.admin.ai.llm.LlmClient;
import com.fitness.admin.ai.llm.LlmClientRegistry;
import com.fitness.admin.ai.metrics.AiMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * AI服务实现 - 通过 {@link LlmClientRegistry} 路由到具体 provider 客户端,
 * 并通过 {@link AiMetrics} 上报 Micrometer 指标。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiServiceImpl implements AiService {

    private final AiConfig aiConfig;
    private final LlmClientRegistry llmRegistry;
    private final AiMetrics aiMetrics;

    @Override
    public String chat(List<ChatMessage> messages) {
        return chatInternal(messages, null).getContent();
    }

    @Override
    public LlmResponse chatWithUsage(List<ChatMessage> messages) {
        return chatInternal(messages, null);
    }

    @Override
    public LlmResponse chatWithUsageStream(List<ChatMessage> messages, Consumer<String> onChunk) {
        long t0 = System.currentTimeMillis();
        LlmClient client = llmRegistry.current(aiConfig);
        String provider = aiConfig.getProvider();
        try {
            LlmResponse resp = client.chatStream(messages, onChunk);
            long latency = System.currentTimeMillis() - t0;
            aiMetrics.recordCall(provider, resp.getPromptTokens(), resp.getCompletionTokens(),
                    latency, true);
            return new LlmResponse(resp.getContent(), resp.getPromptTokens(),
                    resp.getCompletionTokens(), resp.getTotalTokens(), latency);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - t0;
            aiMetrics.recordCall(provider, 0, 0, latency, false);
            log.error("AI服务调用失败: provider={}", provider, e);
            throw new RuntimeException("AI服务调用失败: " + e.getMessage());
        }
    }

    @Override
    public String chat(String userMessage) {
        return chat(List.of(new ChatMessage("user", userMessage)));
    }

    private LlmResponse chatInternal(List<ChatMessage> messages, Consumer<String> onChunk) {
        long t0 = System.currentTimeMillis();
        LlmClient client = llmRegistry.current(aiConfig);
        String provider = aiConfig.getProvider();
        log.debug("LLM 路由: provider={}, client={}", provider, client.getClass().getSimpleName());
        try {
            LlmResponse resp = onChunk != null
                    ? client.chatStream(messages, onChunk)
                    : client.chat(messages);
            long latency = System.currentTimeMillis() - t0;
            aiMetrics.recordCall(provider, resp.getPromptTokens(), resp.getCompletionTokens(),
                    latency, true);
            return new LlmResponse(resp.getContent(), resp.getPromptTokens(),
                    resp.getCompletionTokens(), resp.getTotalTokens(), latency);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - t0;
            aiMetrics.recordCall(provider, 0, 0, latency, false);
            log.error("AI服务调用失败: provider={}", provider, e);
            throw new RuntimeException("AI服务调用失败: " + e.getMessage());
        }
    }
}
