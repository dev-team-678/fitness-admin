package com.fitness.admin.ai.llm;

import com.fitness.admin.ai.service.AiService;

import java.util.List;
import java.util.function.Consumer;

/**
 * LLM 客户端抽象(路由层)。
 *
 * <p>设计目标:把"调哪家 LLM(OpenAI / Claude / DeepSeek)"和"调用流程(超时/重试/quota)"
 * 解耦。{@link com.fitness.admin.ai.service.AiServiceImpl} 不再直接 switch provider,
 * 而是从 Spring 容器中按 provider 名字找到对应的 {@link LlmClient} Bean 调用。
 *
 * <p>新增厂商(如 智谱 GLM、月之暗面)只需实现一个 {@link LlmClient},
 * 在 {@code LlmClientRegistry} 注册一个 key 即可。
 */
public interface LlmClient {

    /**
     * 该客户端对应的 provider 名称(小写)。如 "openai" / "claude" / "deepseek"。
     * 与 {@link com.fitness.admin.ai.config.AiConfig#getProvider()} 一一对应。
     */
    String provider();

    /**
     * 同步调用:返回完整结果(含 token 用量)。
     *
     * @param messages 多轮对话消息
     * @return LLM 响应(内容 + token 计数)
     */
    AiService.LlmResponse chat(List<AiService.ChatMessage> messages);

    /**
     * 流式调用:每拿到一个增量文本块就回调 {@code onChunk}。
     * 用于 SSE 流式输出(见 C5)。
     *
     * <p>默认实现退化为 {@link #chat(List)},回调一次完整内容。
     * 真正支持流的实现应覆盖此方法。
     *
     * @param messages 多轮对话消息
     * @param onChunk  增量回调,可能被调用 1..N 次
     * @return 最终完整响应(含 token 计数)
     */
    default AiService.LlmResponse chatStream(List<AiService.ChatMessage> messages,
                                             Consumer<String> onChunk) {
        AiService.LlmResponse full = chat(messages);
        if (onChunk != null && full != null && full.getContent() != null) {
            onChunk.accept(full.getContent());
        }
        return full;
    }

    /**
     * 健康检查:真实 LLM 联通性(发一条极短 prompt)。
     * 返回是否连通 + 错误信息(可空)。
     */
    default HealthCheckResult healthCheck() {
        try {
            chat(List.of(new AiService.ChatMessage("user", "ping")));
            return HealthCheckResult.ok();
        } catch (Exception e) {
            return HealthCheckResult.fail(e.getMessage());
        }
    }

    /**
     * 联通性测试结果。
     */
    record HealthCheckResult(boolean success, String message) {
        public static HealthCheckResult ok() { return new HealthCheckResult(true, "ok"); }
        public static HealthCheckResult fail(String msg) { return new HealthCheckResult(false, msg); }
    }
}
