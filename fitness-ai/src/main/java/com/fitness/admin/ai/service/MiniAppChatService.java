package com.fitness.admin.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fitness.admin.ai.config.AiConfig;
import com.fitness.admin.ai.dto.*;
import com.fitness.admin.ai.entity.AiChatMessage;
import com.fitness.admin.ai.entity.AiChatSession;
import com.fitness.admin.ai.entity.AiUsageDaily;
import com.fitness.admin.ai.mapper.AiChatMessageMapper;
import com.fitness.admin.ai.mapper.AiChatSessionMapper;
import com.fitness.admin.ai.mapper.AiUsageDailyMapper;
import com.fitness.admin.ai.rag.RagRetriever;
import com.fitness.admin.common.enums.ResultCodeEnum;
import com.fitness.admin.common.exception.BizException;
import com.fitness.admin.common.result.PageResult;
import com.fitness.admin.common.utils.SecurityUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 小程序 AI 对话服务。
 *
 * <p>职责:会话/消息的持久化、AI 调用、上下文拼接、反馈、使用统计 upsert。
 * 计划生成相关逻辑已拆分到 {@link MiniAppPlanGenerateService}。
 */
@Slf4j
@Service
public class MiniAppChatService {

    private final AiChatSessionMapper sessionMapper;
    private final AiChatMessageMapper messageMapper;
    private final AiUsageDailyMapper aiUsageDailyMapper;
    private final AiService aiService;
    private final AiRateLimiter rateLimiter;
    private final AiQuotaGuard quotaGuard;
    private final AiTimeoutGuard timeoutGuard;
    private final AiConfig aiConfig;
    private final RagRetriever ragRetriever;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /**
     * AI 对话异步线程池(Spring 管理,见 {@link com.fitness.admin.ai.config.AiAsyncExecutorConfig})。
     * 替换原先 static Executors.newFixedThreadPool,避免 Servlet 容器关停时线程不退出。
     */
    private final Executor chatAsyncExecutor;

    private static final int STATUS_PROCESSING = 1;
    private static final int STATUS_COMPLETED = 2;
    private static final int STATUS_FAILED = 3;

    public MiniAppChatService(AiChatSessionMapper sessionMapper,
                              AiChatMessageMapper messageMapper,
                              AiUsageDailyMapper aiUsageDailyMapper,
                              AiService aiService,
                              AiRateLimiter rateLimiter,
                              AiQuotaGuard quotaGuard,
                              AiTimeoutGuard timeoutGuard,
                              AiConfig aiConfig,
                              RagRetriever ragRetriever,
                              @Qualifier("aiChatAsyncExecutor") Executor chatAsyncExecutor) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.aiUsageDailyMapper = aiUsageDailyMapper;
        this.aiService = aiService;
        this.rateLimiter = rateLimiter;
        this.quotaGuard = quotaGuard;
        this.timeoutGuard = timeoutGuard;
        this.aiConfig = aiConfig;
        this.ragRetriever = ragRetriever;
        this.chatAsyncExecutor = chatAsyncExecutor;
    }

    public ChatResponse sendChatMessage(ChatRequest request) {
        Long userId = getCurrentUserId();
        rateLimiter.checkAndAcquire();
        quotaGuard.checkChatQuota(userId);

        AiChatSession session;
        if (request.getSessionId() != null) {
            session = sessionMapper.selectById(request.getSessionId());
            if (session == null || !session.getUserId().equals(userId)) {
                throw new BizException("会话不存在");
            }
        } else {
            session = createNewSession(userId);
        }

        AiChatMessage userMessage = new AiChatMessage();
        userMessage.setSessionId(session.getId());
        userMessage.setRole("user");
        userMessage.setContent(request.getMessage());
        userMessage.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(userMessage);

        session.setMessageCount(session.getMessageCount() + 1);
        if (session.getTitle() == null) {
            session.setTitle(request.getMessage().substring(0, Math.min(request.getMessage().length(), 50)));
        }
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);

        // 预占位 AI 消息(状态=processing),客户端拿到 messageId 后即可轮询
        AiChatMessage aiMessage = new AiChatMessage();
        aiMessage.setSessionId(session.getId());
        aiMessage.setRole("assistant");
        aiMessage.setContent("");
        aiMessage.setTokenCount(0);
        aiMessage.setStreamStatus(STATUS_PROCESSING);
        aiMessage.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(aiMessage);

        session.setMessageCount(session.getMessageCount() + 1);
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);

        ChatResponse response = new ChatResponse();
        response.setSessionId(session.getId());
        response.setMessageId(aiMessage.getId());
        response.setContent("");
        response.setStatus(STATUS_PROCESSING);
        response.setTokenCount(0);

        // 异步开关关闭时,直接同步调用(保持旧行为,便于回归测试)
        Boolean asyncEnabled = aiConfig.getAsyncChatEnabled();
        if (Boolean.FALSE.equals(asyncEnabled)) {
            runAiCallSync(aiMessage.getId(), session.getId());
            response.setStatus(STATUS_COMPLETED);
            AiChatMessage latest = messageMapper.selectById(aiMessage.getId());
            if (latest != null) {
                response.setContent(latest.getContent());
                response.setTokenCount(latest.getTokenCount());
            }
            return response;
        }

        // 异步模式:提交后台任务后立即返回
        submitAsync(aiMessage.getId(), session.getId());
        return response;
    }

    /**
     * 客户端轮询消息状态。返回当前消息(可能仍为 processing,也可能已完成/失败)。
     */
    public ChatResponse pollMessage(Long messageId) {
        Long userId = getCurrentUserId();
        AiChatMessage msg = messageMapper.selectById(messageId);
        if (msg == null) {
            throw new BizException("消息不存在");
        }
        AiChatSession session = sessionMapper.selectById(msg.getSessionId());
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BizException("消息不存在");
        }

        ChatResponse response = new ChatResponse();
        response.setSessionId(msg.getSessionId());
        response.setMessageId(msg.getId());
        response.setContent(msg.getContent() != null ? msg.getContent() : "");
        response.setTokenCount(msg.getTokenCount() != null ? msg.getTokenCount() : 0);
        response.setStatus(msg.getStreamStatus() != null ? msg.getStreamStatus() : STATUS_COMPLETED);
        return response;
    }

    private void submitAsync(Long messageId, Long sessionId) {
        try {
            chatAsyncExecutor.execute(() -> runAiCallSync(messageId, sessionId));
        } catch (Exception e) {
            log.error("提交 AI 异步任务失败,降级为同步处理: messageId={}", messageId, e);
            runAiCallSync(messageId, sessionId);
        }
    }

    /**
     * 执行真实的 AI 调用并把结果写回 ai_chat_message.streamStatus。
     * 同步方法,可被同步或异步两种模式复用。
     */
    private void runAiCallSync(Long messageId, Long sessionId) {
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
            AiService.LlmResponse resp = timeoutGuard.callWithTimeout(
                    timeoutSec, () -> aiService.chatWithUsage(chatMessages));
            long latencyMs = System.currentTimeMillis() - t0;

            // 真实 token 计数:由 LLM 上报,优先用;若 LLM 不返回,回退 0(不再用字符数伪造)
            int totalTokens = resp.getTotalTokens();
            try {
                quotaGuard.checkAndAccumulateTokens(totalTokens);
            } catch (com.fitness.admin.common.exception.BizException quotaEx) {
                finalizeMessage(messageId, sessionId,
                        "今日 AI token 用量已达上限,请明日再试。", STATUS_FAILED, List.of());
                return;
            }

            finalizeMessageWithUsage(messageId, sessionId, resp.getContent(),
                    STATUS_COMPLETED, refs, totalTokens, latencyMs);
        } catch (Exception e) {
            log.error("AI服务调用失败: messageId={}", messageId, e);
            String errMsg = "抱歉,AI服务暂时不可用,请稍后再试。";
            finalizeMessage(messageId, sessionId, errMsg, STATUS_FAILED, List.of());
        }
    }

    private void finalizeMessageWithUsage(Long messageId, Long sessionId, String content, int status,
                                          List<AiChatMessage.RagReference> refs,
                                          int totalTokens, long latencyMs) {
        try {
            AiChatMessage msg = messageMapper.selectById(messageId);
            if (msg == null) {
                log.warn("异步任务结束时消息已不存在: messageId={}", messageId);
                return;
            }
            msg.setContent(content);
            msg.setTokenCount(totalTokens);
            msg.setStreamStatus(status);
            if (refs != null && !refs.isEmpty()) {
                try {
                    msg.setRagRefs(objectMapper.writeValueAsString(refs));
                } catch (JsonProcessingException e) {
                    log.warn("序列化 ragRefs 失败: {}", e.getMessage());
                }
            } else if (status == STATUS_COMPLETED) {
                msg.setRagRefs(null);
            }
            messageMapper.updateById(msg);

            if (status == STATUS_COMPLETED) {
                upsertTodayUsage();
            }
            log.info("AI 调用完成: messageId={}, totalTokens={}, latencyMs={}",
                    messageId, totalTokens, latencyMs);
        } catch (Exception e) {
            log.error("写回 AI 消息结果失败: messageId={}, status={}", messageId, status, e);
        }
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

    private void finalizeMessage(Long messageId, Long sessionId, String content, int status,
                                 List<AiChatMessage.RagReference> refs) {
        try {
            AiChatMessage msg = messageMapper.selectById(messageId);
            if (msg == null) {
                log.warn("异步任务结束时消息已不存在: messageId={}", messageId);
                return;
            }
            msg.setContent(content);
            msg.setTokenCount(content != null ? content.length() : 0);
            msg.setStreamStatus(status);
            if (refs != null && !refs.isEmpty()) {
                try {
                    msg.setRagRefs(objectMapper.writeValueAsString(refs));
                } catch (JsonProcessingException e) {
                    log.warn("序列化 ragRefs 失败: {}", e.getMessage());
                }
            } else if (status == STATUS_COMPLETED) {
                msg.setRagRefs(null);
            }
            messageMapper.updateById(msg);

            if (status == STATUS_COMPLETED) {
                upsertTodayUsage();
            }
        } catch (Exception e) {
            log.error("写回 AI 消息结果失败: messageId={}, status={}", messageId, status, e);
        }
    }

    public PageResult<AiChatMessage> getChatMessages(Long sessionId, Integer pageNum, Integer pageSize) {
        Long userId = getCurrentUserId();

        AiChatSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BizException("会话不存在");
        }

        Page<AiChatMessage> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<AiChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiChatMessage::getSessionId, sessionId)
               .orderByAsc(AiChatMessage::getCreatedAt);
        Page<AiChatMessage> result = messageMapper.selectPage(page, wrapper);

        return PageResult.of(result);
    }

    public PageResult<AiChatSession> getChatSessions(Integer pageNum, Integer pageSize) {
        Long userId = getCurrentUserId();

        Page<AiChatSession> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<AiChatSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiChatSession::getUserId, userId)
               .orderByDesc(AiChatSession::getUpdatedAt);
        Page<AiChatSession> result = sessionMapper.selectPage(page, wrapper);

        return PageResult.of(result);
    }

    public void feedback(Long sessionId, Long msgId, Integer feedback) {
        Long userId = getCurrentUserId();

        AiChatSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BizException("会话不存在");
        }

        AiChatMessage message = messageMapper.selectById(msgId);
        if (message == null || !message.getSessionId().equals(sessionId)) {
            throw new BizException("消息不存在");
        }

        message.setFeedback(feedback);
        messageMapper.updateById(message);
    }

    private List<AiService.ChatMessage> buildChatMessages(Long sessionId) {
        List<AiService.ChatMessage> messages = new ArrayList<>();
        messages.add(new AiService.ChatMessage("system",
                "你是一个专业的AI健身助手,名叫FitBot。你擅长制定训练计划、解答健身问题、提供营养建议。请用友好专业的语气回答。"));

        int historyLimit = aiConfig.getChatHistoryLimit() != null ? aiConfig.getChatHistoryLimit() : 6;
        LambdaQueryWrapper<AiChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiChatMessage::getSessionId, sessionId)
               .orderByDesc(AiChatMessage::getCreatedAt)
               .last("LIMIT " + historyLimit);
        List<AiChatMessage> history = messageMapper.selectList(wrapper);

        for (int i = history.size() - 1; i >= 0; i--) {
            AiChatMessage msg = history.get(i);
            messages.add(new AiService.ChatMessage(msg.getRole(), msg.getContent()));
        }
        return messages;
    }

    private AiChatSession createNewSession(Long userId) {
        AiChatSession session = new AiChatSession();
        session.setUserId(userId);
        session.setSessionType("fitness");
        session.setMessageCount(0);
        session.setStatus(1);
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.insert(session);
        return session;
    }

    private Long getCurrentUserId() {
        Long userId = SecurityUtil.getCurrentUserId();
        if (userId == null) {
            throw new BizException(ResultCodeEnum.UNAUTHORIZED);
        }
        return userId;
    }

    /**
     * 实时更新今日 AI 使用统计 (upsert ai_usage_daily)。
     */
    private void upsertTodayUsage() {
        try {
            LocalDate today = LocalDate.now();
            LocalDateTime dayStart = today.atStartOfDay();
            LocalDateTime dayEnd = today.atTime(23, 59, 59);

            Long chatSessions = sessionMapper.selectCount(
                    Wrappers.<AiChatSession>lambdaQuery()
                            .between(AiChatSession::getCreatedAt, dayStart, dayEnd)
            );

            List<AiChatMessage> dayMessages = messageMapper.selectList(
                    Wrappers.<AiChatMessage>lambdaQuery()
                            .between(AiChatMessage::getCreatedAt, dayStart, dayEnd)
            );
            int totalMessages = dayMessages.size();

            int positiveFeedback = 0;
            int negativeFeedback = 0;
            for (AiChatMessage msg : dayMessages) {
                if (msg.getFeedback() != null) {
                    if (msg.getFeedback() > 0) positiveFeedback++;
                    else if (msg.getFeedback() < 0) negativeFeedback++;
                }
            }

            int feedbackTotal = positiveFeedback + negativeFeedback;
            BigDecimal satisfactionRate = feedbackTotal > 0
                    ? BigDecimal.valueOf(positiveFeedback)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(feedbackTotal), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            long totalTokens = dayMessages.stream()
                    .filter(m -> "assistant".equals(m.getRole()) && m.getTokenCount() != null)
                    .mapToLong(AiChatMessage::getTokenCount)
                    .sum();

            long assistantMessages = dayMessages.stream()
                    .filter(m -> "assistant".equals(m.getRole()))
                    .count();
            long ragHitMessages = dayMessages.stream()
                    .filter(m -> "assistant".equals(m.getRole())
                            && m.getRagRefs() != null && !m.getRagRefs().isBlank())
                    .count();
            BigDecimal ragHitRate = assistantMessages > 0
                    ? BigDecimal.valueOf(ragHitMessages)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(assistantMessages), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            int avgResponseTimeMs = 0;
            if (assistantMessages > 0) {
                long avgTokens = dayMessages.stream()
                        .filter(m -> "assistant".equals(m.getRole()) && m.getTokenCount() != null)
                        .mapToLong(AiChatMessage::getTokenCount)
                        .sum() / Math.max(assistantMessages, 1);
                avgResponseTimeMs = (int) (avgTokens * 10);
            }

            AiUsageDaily existing = aiUsageDailyMapper.selectOne(
                    Wrappers.<AiUsageDaily>lambdaQuery()
                            .eq(AiUsageDaily::getStatDate, today)
            );

            if (existing != null) {
                existing.setTotalChatSessions(chatSessions.intValue());
                existing.setTotalChatMessages(totalMessages);
                existing.setTotalTokensUsed(totalTokens);
                existing.setPositiveFeedbackCount(positiveFeedback);
                existing.setNegativeFeedbackCount(negativeFeedback);
                existing.setSatisfactionRate(satisfactionRate);
                existing.setAvgResponseTimeMs(avgResponseTimeMs);
                existing.setRagHitRate(ragHitRate);
                aiUsageDailyMapper.updateById(existing);
            } else {
                AiUsageDaily record = new AiUsageDaily();
                record.setStatDate(today);
                record.setTotalChatSessions(chatSessions.intValue());
                record.setTotalChatMessages(totalMessages);
                record.setTotalTokensUsed(totalTokens);
                record.setPositiveFeedbackCount(positiveFeedback);
                record.setNegativeFeedbackCount(negativeFeedback);
                record.setSatisfactionRate(satisfactionRate);
                record.setAvgResponseTimeMs(avgResponseTimeMs);
                record.setRagHitRate(ragHitRate);
                record.setCreatedAt(LocalDateTime.now());
                aiUsageDailyMapper.insert(record);
            }
        } catch (Exception e) {
            log.warn("实时更新AI使用统计失败,将由定时任务补偿: {}", e.getMessage());
        }
    }
}
