package com.fitness.admin.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fitness.admin.ai.config.AiConfig;
import com.fitness.admin.ai.entity.AiChatMessage;
import com.fitness.admin.ai.entity.AiChatSession;
import com.fitness.admin.ai.mapper.AiChatMessageMapper;
import com.fitness.admin.ai.mapper.AiChatSessionMapper;
import com.fitness.admin.ai.rag.RagRetriever;
import com.fitness.admin.common.enums.ResultCodeEnum;
import com.fitness.admin.common.exception.BizException;
import com.fitness.admin.common.utils.SecurityUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * AI 对话 SSE 流式服务。
 *
 * <p>把 LLM 的增量输出以 {@code text/event-stream} 推给前端。
 * 当前实现兼容"非流式"客户端(把完整 reply 当一个 chunk 推),
 * 但已用 {@link com.fitness.admin.ai.llm.LlmClient#chatStream} 走流式接口,
 * 未来切到原生 SSE 的 LLM 客户端(OpenAI stream / Claude stream)即可零成本升级。
 *
 * <p>生命周期:
 * <ul>
 *   <li>前端 GET /miniapp/ai/chat/stream?sessionId=…  →  返回 SseEmitter(30 min 超时)</li>
 *   <li>后端先把 meta(sessionId/messageId) 推一个事件,前端开始显示 loading</li>
 *   <li>调用 LLM.chatStream,每收到一个 chunk 就 send("chunk", text)</li>
 *   <li>完成后 send("done", {tokens}) → complete(); 出错 send("error", msg) → completeWithError</li>
 * </ul>
 */
@Slf4j
@Service
public class AiSseStreamService {

    private final AiChatSessionMapper sessionMapper;
    private final AiChatMessageMapper messageMapper;
    private final AiService aiService;
    private final AiRateLimiter rateLimiter;
    private final AiQuotaGuard quotaGuard;
    private final AiTimeoutGuard timeoutGuard;
    private final AiConfig aiConfig;
    private final RagRetriever ragRetriever;
    private final Executor chatAsyncExecutor;

    public AiSseStreamService(AiChatSessionMapper sessionMapper,
                              AiChatMessageMapper messageMapper,
                              AiService aiService,
                              AiRateLimiter rateLimiter,
                              AiQuotaGuard quotaGuard,
                              AiTimeoutGuard timeoutGuard,
                              AiConfig aiConfig,
                              RagRetriever ragRetriever,
                              @Qualifier("aiChatAsyncExecutor") Executor chatAsyncExecutor) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.aiService = aiService;
        this.rateLimiter = rateLimiter;
        this.quotaGuard = quotaGuard;
        this.timeoutGuard = timeoutGuard;
        this.aiConfig = aiConfig;
        this.ragRetriever = ragRetriever;
        this.chatAsyncExecutor = chatAsyncExecutor;
    }

    private static final int STATUS_PROCESSING = 1;
    private static final int STATUS_COMPLETED = 2;
    private static final int STATUS_FAILED = 3;

    public SseEmitter stream(Long sessionId, Long resumeMessageId) {
        // 30 分钟超时 — 大多数 AI 一次对话不会这么久;超时就关 emitter
        SseEmitter emitter = new SseEmitter(30L * 60 * 1000);

        Long userId = SecurityUtil.getCurrentUserId();
        if (userId == null) {
            emitter.completeWithError(new BizException(ResultCodeEnum.UNAUTHORIZED));
            return emitter;
        }

        AiChatSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            emitter.completeWithError(new BizException("会话不存在"));
            return emitter;
        }

        try {
            rateLimiter.checkAndAcquire();
            quotaGuard.checkChatQuota(userId);
        } catch (BizException e) {
            emitter.completeWithError(e);
            return emitter;
        }

        // 客户端断连(浏览器关闭)时回调 → 不再继续推
        emitter.onCompletion(() -> log.debug("SSE emitter 完成: sessionId={}", sessionId));
        emitter.onTimeout(() -> log.warn("SSE emitter 超时: sessionId={}", sessionId));
        emitter.onError(t -> log.warn("SSE emitter 出错: sessionId={}, err={}", sessionId, t.toString()));

        // 提交到异步线程池(避免 Tomcat worker 被长 LLM 调用拖死)
        chatAsyncExecutor.execute(() -> runStream(session, resumeMessageId, emitter));
        return emitter;
    }

    private void runStream(AiChatSession session, Long resumeMessageId, SseEmitter emitter) {
        Long sessionId = session.getId();
        Long userId = session.getUserId();

        // 1) 创建新 message 记录(预占位)
        AiChatMessage aiMessage = resumeMessageId != null
                ? messageMapper.selectById(resumeMessageId)
                : null;
        if (aiMessage == null) {
            aiMessage = new AiChatMessage();
            aiMessage.setSessionId(sessionId);
            aiMessage.setRole("assistant");
            aiMessage.setContent("");
            aiMessage.setTokenCount(0);
            aiMessage.setStreamStatus(STATUS_PROCESSING);
            aiMessage.setCreatedAt(LocalDateTime.now());
            messageMapper.insert(aiMessage);
        } else {
            aiMessage.setStreamStatus(STATUS_PROCESSING);
            aiMessage.setContent("");
            messageMapper.updateById(aiMessage);
        }
        final Long messageId = aiMessage.getId();

        // 2) 推 meta 事件
        try {
            emitter.send(SseEmitter.event().name("meta")
                    .data("{\"sessionId\":" + sessionId + ",\"messageId\":" + messageId + "}"));
        } catch (IOException e) {
            log.warn("SSE meta 发送失败: sessionId={}", sessionId, e);
            emitter.completeWithError(e);
            return;
        }

        // 3) 构造 LLM 输入
        try {
            List<AiService.ChatMessage> chatMessages = buildChatMessages(sessionId);
            String userQuery = extractLastUserMessage(chatMessages);
            List<AiChatMessage.RagReference> refs = ragRetriever.retrieve(userQuery);
            if (!refs.isEmpty()) {
                augmentWithRag(chatMessages, refs);
            }

            int timeoutSec = aiConfig.getAsyncChatTimeoutSeconds() != null
                    ? aiConfig.getAsyncChatTimeoutSeconds() : 90;

            long t0 = System.currentTimeMillis();
            StringBuilder accumulated = new StringBuilder();
            AiService.LlmResponse resp = timeoutGuard.callWithTimeout(timeoutSec, () ->
                    aiService.chatWithUsageStream(chatMessages, chunk -> {
                        try {
                            emitter.send(SseEmitter.event().name("chunk").data(chunk));
                            accumulated.append(chunk);
                        } catch (IOException e) {
                            throw new RuntimeException("SSE 推送失败", e);
                        }
                    }));
            long latencyMs = System.currentTimeMillis() - t0;

            // 4) 真实 token 计数
            int totalTokens = resp.getTotalTokens();
            try {
                quotaGuard.checkAndAccumulateTokens(totalTokens);
            } catch (BizException quotaEx) {
                emitter.send(SseEmitter.event().name("error").data(quotaEx.getMessage()));
                aiMessage.setContent(accumulated.toString());
                aiMessage.setStreamStatus(STATUS_FAILED);
                aiMessage.setTokenCount(totalTokens);
                messageMapper.updateById(aiMessage);
                emitter.complete();
                return;
            }

            // 5) 推 done 事件 + 写库
            emitter.send(SseEmitter.event().name("done")
                    .data("{\"tokens\":" + totalTokens + ",\"latencyMs\":" + latencyMs + "}"));

            aiMessage.setContent(accumulated.toString());
            aiMessage.setTokenCount(totalTokens);
            aiMessage.setStreamStatus(STATUS_COMPLETED);
            messageMapper.updateById(aiMessage);
            emitter.complete();
        } catch (Exception e) {
            log.error("SSE 流式对话失败: sessionId={}, messageId={}", sessionId, messageId, e);
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(e.getMessage() == null ? "服务异常" : e.getMessage()));
            } catch (IOException ignored) {
                // 客户端已断开
            }
            aiMessage.setStreamStatus(STATUS_FAILED);
            aiMessage.setContent("抱歉,AI 服务暂时不可用,请稍后再试。");
            messageMapper.updateById(aiMessage);
            emitter.completeWithError(e);
        }
    }

    private List<AiService.ChatMessage> buildChatMessages(Long sessionId) {
        List<AiService.ChatMessage> messages = new ArrayList<>();
        messages.add(new AiService.ChatMessage("system", aiConfig.getSystemPrompt()));
        int historyLimit = aiConfig.getChatHistoryLimit() != null ? aiConfig.getChatHistoryLimit() : 6;
        LambdaQueryWrapper<AiChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiChatMessage::getSessionId, sessionId)
                .orderByDesc(AiChatMessage::getCreatedAt)
                .last("LIMIT " + historyLimit);
        List<AiChatMessage> history = messageMapper.selectList(wrapper);
        for (int i = history.size() - 1; i >= 0; i--) {
            AiChatMessage m = history.get(i);
            messages.add(new AiService.ChatMessage(m.getRole(), m.getContent()));
        }
        return messages;
    }

    private String extractLastUserMessage(List<AiService.ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            AiService.ChatMessage m = messages.get(i);
            if ("user".equals(m.getRole())) return m.getContent();
        }
        return "";
    }

    private void augmentWithRag(List<AiService.ChatMessage> messages,
                                List<AiChatMessage.RagReference> refs) {
        if (messages.isEmpty()) return;
        StringBuilder augmented = new StringBuilder();
        augmented.append(messages.get(0).getContent())
                .append("\n\n# 参考资料\n请基于以下参考资料回答用户问题,引用时附 [来源#index] 标记。\n");
        for (int i = 0; i < refs.size(); i++) {
            AiChatMessage.RagReference r = refs.get(i);
            augmented.append('[').append(i + 1).append("] ");
            if (r.getTitle() != null) augmented.append(r.getTitle()).append(" — ");
            if (r.getCategoryName() != null) augmented.append('(').append(r.getCategoryName()).append(") ");
            augmented.append("(score=").append(String.format("%.2f", r.getScore() == null ? 0.0 : r.getScore())).append(")\n");
        }
        messages.set(0, new AiService.ChatMessage("system", augmented.toString()));
    }
}
