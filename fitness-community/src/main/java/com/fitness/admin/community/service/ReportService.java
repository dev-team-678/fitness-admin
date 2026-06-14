package com.fitness.admin.community.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.admin.common.enums.ReportStatusEnum;
import com.fitness.admin.common.exception.BizException;
import com.fitness.admin.common.utils.SecurityUtil;
import com.fitness.admin.community.dto.CreateReportRequest;
import com.fitness.admin.community.entity.Report;
import com.fitness.admin.community.mapper.ReportMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportMapper reportMapper;
    private final ObjectMapper objectMapper;

    public Page<Report> queryPage(Integer pageNum, Integer pageSize) {
        Page<Report> page = new Page<>(pageNum, pageSize);
        return reportMapper.selectPage(page, null);
    }

    /**
     * 处理举报。action: confirmed | dismissed, mapped to status 1 | 2.
     * reason 写入 handle_result;handlerId 写入 handler_id。
     */
    public void handle(Long id, String action, String reason, Long handlerId) {
        ReportStatusEnum status = switch (action == null ? "" : action) {
            case "confirmed" -> ReportStatusEnum.CONFIRMED;
            case "dismissed" -> ReportStatusEnum.DISMISSED;
            default -> throw new IllegalArgumentException("action 仅支持 confirmed / dismissed");
        };
        Report report = new Report();
        report.setId(id);
        report.setStatus(status);
        report.setHandleResult(reason);
        report.setHandlerId(handlerId);
        reportMapper.updateById(report);
    }

    /**
     * 小程序用户提交举报
     */
    public void createReport(CreateReportRequest request) {
        Long userId = SecurityUtil.getCurrentUserId();
        if (userId == null) {
            throw new BizException("请先登录");
        }

        // 参数校验
        if (request.getTargetId() == null) {
            throw new BizException("举报目标不能为空");
        }
        String targetType = request.getTargetType();
        if (targetType == null || (!"post".equals(targetType) && !"comment".equals(targetType))) {
            throw new BizException("举报目标类型无效");
        }
        if (request.getReason() == null || request.getReason().isBlank()) {
            throw new BizException("请选择举报原因");
        }

        // 防重复举报 (同一用户 + 同一目标, 24小时内)
        LambdaQueryWrapper<Report> dupWrapper = new LambdaQueryWrapper<>();
        dupWrapper.eq(Report::getReporterId, userId)
                  .eq(Report::getTargetId, request.getTargetId())
                  .eq(Report::getTargetType, targetType)
                  .ge(Report::getCreatedAt, LocalDateTime.now().minusHours(24));
        Long dupCount = reportMapper.selectCount(dupWrapper);
        if (dupCount > 0) {
            throw new BizException("您已举报过该内容，请勿重复提交");
        }

        // 序列化图片列表
        String imagesJson = null;
        List<String> images = request.getImages();
        if (images != null && !images.isEmpty()) {
            try {
                imagesJson = objectMapper.writeValueAsString(images);
            } catch (JsonProcessingException e) {
                log.warn("序列化举报图片失败", e);
            }
        }

        Report report = new Report();
        report.setReporterId(userId);
        report.setTargetId(request.getTargetId());
        report.setTargetType(targetType);
        report.setReason(request.getReason());
        report.setDescription(request.getDescription());
        report.setImages(imagesJson);
        report.setStatus(ReportStatusEnum.PENDING); // 待处理
        report.setCreatedAt(LocalDateTime.now());
        report.setUpdatedAt(LocalDateTime.now());
        reportMapper.insert(report);

        log.info("用户{}提交举报: targetId={}, targetType={}, reason={}",
                userId, request.getTargetId(), targetType, request.getReason());
    }
}
