package com.fitness.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fitness.admin.ai.service.AiConnectionTestService;
import com.fitness.admin.common.annotation.LogOperation;
import com.fitness.admin.common.base.BaseController;
import com.fitness.admin.common.result.R;
import com.fitness.admin.common.utils.SecretMasker;
import com.fitness.admin.common.utils.SecurityUtil;
import com.fitness.admin.system.config.NacosConfigPublisher;
import com.fitness.admin.system.entity.AiConfigAuditLog;
import com.fitness.admin.system.entity.SysConfig;
import com.fitness.admin.system.mapper.AiConfigAuditLogMapper;
import com.fitness.admin.system.service.SysConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Tag(name = "系统配置")
@RestController
@RequestMapping("/sys-config")
@RequiredArgsConstructor
@SaCheckPermission("sys:config:read")
public class SysConfigController extends BaseController {

    private final SysConfigService sysConfigService;
    private final NacosConfigPublisher nacosConfigPublisher;
    private final AiConnectionTestService aiConnectionTestService;
    private final AiConfigAuditLogMapper aiConfigAuditLogMapper;

    @Operation(summary = "配置列表")
    @GetMapping("/list")
    public R<List<SysConfig>> list() {
        return success(sysConfigService.list());
    }

    @LogOperation(action = "新增", module = "系统配置")
    @Operation(summary = "保存配置")
    @PostMapping
    @SaCheckPermission("sys:config:create")
    public R<Void> save(@RequestBody SysConfig config) {
        sysConfigService.save(config);
        return success();
    }

    @LogOperation(action = "编辑", module = "系统配置")
    @Operation(summary = "按key更新配置")
    @PutMapping("/{configKey}")
    @SaCheckPermission("sys:config:update")
    public R<Void> updateByKey(@PathVariable String configKey, @RequestBody Map<String, Object> body) {
        Object value = body.get("configValue");
        Object description = body.get("description");
        sysConfigService.saveByKey(configKey,
                value == null ? "" : String.valueOf(value),
                description == null ? null : String.valueOf(description));
        return success();
    }

    @LogOperation(action = "删除", module = "系统配置")
    @Operation(summary = "删除配置")
    @DeleteMapping("/{id}")
    @SaCheckPermission("sys:config:delete")
    public R<Void> delete(@PathVariable Long id) {
        sysConfigService.delete(id);
        return success();
    }

    @Operation(summary = "获取AI配置(api-key 已脱敏)")
    @GetMapping("/ai-config")
    public R<Map<String, Object>> getAiConfig() {
        return success(nacosConfigPublisher.getAiConfig());
    }

    @LogOperation(action = "编辑", module = "系统配置")
    @Operation(summary = "更新AI配置(写审计日志)")
    @PutMapping("/ai-config")
    @SaCheckPermission("sys:config:update")
    public R<Void> updateAiConfig(@RequestBody Map<String, Object> configMap,
                                  HttpServletRequest request) {
        Map<String, Object> previous;
        try {
            previous = nacosConfigPublisher.getAiConfigUnmasked();
        } catch (Exception e) {
            log.warn("读取旧 ai 配置用于审计失败,继续推送: {}", e.getMessage());
            previous = new HashMap<>();
        }
        nacosConfigPublisher.publishAiConfig(configMap);
        writeAuditLog(previous, configMap, request);
        return success();
    }

    private void writeAuditLog(Map<String, Object> oldMap, Map<String, Object> newMap,
                               HttpServletRequest request) {
        try {
            Long operatorId = SecurityUtil.getCurrentUserId();
            String operatorName = currentUsername();
            String clientIp = clientIp(request);
            for (Map.Entry<String, Object> e : newMap.entrySet()) {
                String field = e.getKey();
                Object oldVal = oldMap.get(field);
                Object newVal = e.getValue();
                if (Objects.equals(oldVal, newVal)) continue;
                AiConfigAuditLog rec = new AiConfigAuditLog();
                rec.setDataId(NacosConfigPublisher.AI_DATA_ID);
                rec.setField(field);
                rec.setOp("update");
                rec.setOldValueMasked(SecretMasker.mask(oldVal == null ? "" : String.valueOf(oldVal)));
                rec.setNewValueMasked(SecretMasker.mask(newVal == null ? "" : String.valueOf(newVal)));
                rec.setOperatorId(operatorId);
                rec.setOperatorName(operatorName);
                rec.setClientIp(clientIp);
                aiConfigAuditLogMapper.insert(rec);
            }
        } catch (Exception ex) {
            log.error("写 AI 配置审计日志失败", ex);
        }
    }

    private String currentUsername() {
        try {
            Object loginId = StpUtil.getLoginId();
            return loginId == null ? "system" : String.valueOf(loginId);
        } catch (Exception e) {
            return "system";
        }
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) return null;
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) ip = request.getRemoteAddr();
        return ip;
    }

    @Operation(summary = "测试AI连接(真实调用 LLM)")
    @PostMapping("/ai-config/test-connection")
    @SaCheckPermission("sys:config:update")
    public R<Map<String, Object>> testAiConnection(@RequestBody(required = false) Map<String, Object> configOverride) {
        Map<String, Object> effective = new LinkedHashMap<>(nacosConfigPublisher.getAiConfigUnmasked());
        if (configOverride != null) {
            configOverride.forEach((k, v) -> { if (v != null) effective.put(k, v); });
        }
        return success(aiConnectionTestService.test(effective));
    }

    @Operation(summary = "AI配置变更审计日志")
    @GetMapping("/ai-config/audit-log")
    public R<Map<String, Object>> auditLog(@RequestParam(defaultValue = "1") Integer pageNum,
                                          @RequestParam(defaultValue = "20") Integer pageSize) {
        Page<AiConfigAuditLog> page = new Page<>(pageNum, pageSize);
        IPage<AiConfigAuditLog> result = aiConfigAuditLogMapper.selectPage(page,
                new LambdaQueryWrapper<AiConfigAuditLog>()
                        .orderByDesc(AiConfigAuditLog::getCreatedAt));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", result.getRecords());
        data.put("total", result.getTotal());
        return R.ok(data);
    }
}
