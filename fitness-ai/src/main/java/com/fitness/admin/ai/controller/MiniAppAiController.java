package com.fitness.admin.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.fitness.admin.ai.dto.*;
import com.fitness.admin.ai.entity.AiChatMessage;
import com.fitness.admin.ai.entity.AiChatSession;
import com.fitness.admin.ai.entity.AiPlan;
import com.fitness.admin.ai.service.AiSseStreamService;
import com.fitness.admin.ai.service.MiniAppAiService;
import com.fitness.admin.common.base.BaseController;
import com.fitness.admin.common.result.PageResult;
import com.fitness.admin.common.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 小程序AI接口
 */
@Tag(name = "小程序-AI模块")
@RestController
@RequestMapping("/miniapp/ai")
@RequiredArgsConstructor
@SaCheckLogin
public class MiniAppAiController extends BaseController {

    private final MiniAppAiService miniAppAiService;
    private final AiSseStreamService sseStreamService;

    @Operation(summary = "发送AI对话消息")
    @PostMapping("/chat/send")
    public R<ChatResponse> sendChatMessage(@Valid @RequestBody ChatRequest request) {
        return success(miniAppAiService.sendChatMessage(request));
    }

    @Operation(summary = "轮询AI消息状态(异步模式)")
    @GetMapping("/chat/messages/{messageId}/status")
    public R<ChatResponse> pollMessage(@PathVariable Long messageId) {
        return success(miniAppAiService.pollMessage(messageId));
    }

    /**
     * SSE 流式对话。前端用 EventSource({withCredentials:true}) 订阅。
     * <p>事件序列:
     * <ul>
     *   <li>{@code meta}: 会话信息(JSON,含 sessionId / messageId)</li>
     *   <li>{@code chunk}: 增量文本(plain text)</li>
     *   <li>{@code done}: 结束事件,data=token 总数 + 完整 content 长度</li>
     *   <li>{@code error}: 异常事件,data=错误消息</li>
     * </ul>
     */
    @Operation(summary = "SSE流式对话")
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam Long sessionId,
                                 @RequestParam(required = false) Long messageId) {
        return sseStreamService.stream(sessionId, messageId);
    }

    @Operation(summary = "会话消息列表")
    @GetMapping("/chat/{sessionId}/messages")
    public R<PageResult<AiChatMessage>> getChatMessages(
            @PathVariable Long sessionId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return success(miniAppAiService.getChatMessages(sessionId, pageSize, pageNum));
    }

    @Operation(summary = "会话列表")
    @GetMapping("/chat/sessions")
    public R<PageResult<AiChatSession>> getChatSessions(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return success(miniAppAiService.getChatSessions(pageNum, pageSize));
    }

    @Operation(summary = "消息反馈")
    @PostMapping("/chat/{sessionId}/messages/{msgId}/feedback")
    public R<Void> feedback(@PathVariable Long sessionId,
                            @PathVariable Long msgId,
                            @RequestBody FeedbackRequest request) {
        miniAppAiService.feedback(sessionId, msgId, request.getFeedback());
        return success();
    }

    @Operation(summary = "生成AI计划")
    @PostMapping("/plan/generate")
    public R<GeneratePlanResponse> generatePlan(@RequestBody GeneratePlanRequest request) {
        return success(miniAppAiService.generatePlan(request));
    }

    @Operation(summary = "确认AI计划")
    @PostMapping("/plan/{id}/confirm")
    public R<ConfirmPlanResponse> confirmPlan(@PathVariable Long id) {
        return success(miniAppAiService.confirmPlan(id));
    }

    @Operation(summary = "AI计划详情")
    @GetMapping("/plan/{id}")
    public R<AiPlan> getPlanDetail(@PathVariable Long id) {
        return success(miniAppAiService.getPlanDetail(id));
    }

    @Operation(summary = "AI计划列表")
    @GetMapping("/plan/list")
    public R<PageResult<AiPlan>> getPlanList(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String status) {
        return success(miniAppAiService.getPlanList(pageNum, pageSize, status));
    }
}