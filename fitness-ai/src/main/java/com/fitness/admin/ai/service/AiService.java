package com.fitness.admin.ai.service;

import java.util.List;

/**
 * AI服务接口
 */
public interface AiService {

    /**
     * 发送聊天消息并获取响应(不返回 token 用量,简单文本场景用)
     */
    String chat(List<ChatMessage> messages);

    /**
     * 发送聊天消息并获取结构化响应(含 LLM 上报的 usage.total_tokens)。
     * 推荐用于需要真实 token 计数 / 用量配额 / 计费 的链路。
     */
    LlmResponse chatWithUsage(List<ChatMessage> messages);

    /**
     * 单轮对话
     */
    String chat(String userMessage);

    /**
     * 流式对话:每拿到一个增量文本块就回调 {@code onChunk}。
     * 返回最终 {@link LlmResponse}(含 token 计数)。
     * 默认实现退化为同步 chat,再整体回调一次。
     */
    default LlmResponse chatWithUsageStream(List<ChatMessage> messages,
                                            java.util.function.Consumer<String> onChunk) {
        LlmResponse full = chatWithUsage(messages);
        if (onChunk != null && full != null && full.getContent() != null) {
            onChunk.accept(full.getContent());
        }
        return full;
    }

    /**
     * 消息类
     */
    class ChatMessage {
        private String role; // system, user, assistant
        private String content;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    /**
     * LLM 响应(扩展版):reply 文本 + token 用量。
     * promptTokens / completionTokens 在 LLM 没返回时为 0(此时 totalTokens 也为 0)。
     */
    class LlmResponse {
        private final String content;
        private final int promptTokens;
        private final int completionTokens;
        private final int totalTokens;
        private final long latencyMs;

        public LlmResponse(String content, int promptTokens, int completionTokens, int totalTokens, long latencyMs) {
            this.content = content;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
            this.latencyMs = latencyMs;
        }

        public String getContent() { return content; }
        public int getPromptTokens() { return promptTokens; }
        public int getCompletionTokens() { return completionTokens; }
        public int getTotalTokens() { return totalTokens; }
        public long getLatencyMs() { return latencyMs; }
    }
}
