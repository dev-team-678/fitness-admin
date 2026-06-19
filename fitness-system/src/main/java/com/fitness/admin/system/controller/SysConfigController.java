package com.fitness.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.fitness.admin.common.base.BaseController;
import com.fitness.admin.common.result.R;
import com.fitness.admin.system.entity.SysConfig;
import com.fitness.admin.system.service.SysConfigService;
import com.fitness.admin.common.annotation.LogOperation;
import com.fitness.admin.system.config.NacosConfigPublisher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "系统配置")
@RestController
@RequestMapping("/sys-config")
@RequiredArgsConstructor
@SaCheckPermission("sys:config:read")
public class SysConfigController extends BaseController {

    private final SysConfigService sysConfigService;
    private final NacosConfigPublisher nacosConfigPublisher;

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

    @Operation(summary = "获取AI配置")
    @GetMapping("/ai-config")
    public R<Map<String, Object>> getAiConfig() {
        return success(nacosConfigPublisher.getAiConfig());
    }

    @LogOperation(action = "编辑", module = "系统配置")
    @Operation(summary = "更新AI配置")
    @PutMapping("/ai-config")
    @SaCheckPermission("sys:config:update")
    public R<Void> updateAiConfig(@RequestBody Map<String, Object> configMap) {
        nacosConfigPublisher.publishAiConfig(configMap);
        return success();
    }

    @Operation(summary = "测试AI连接")
    @PostMapping("/ai-config/test-connection")
    @SaCheckPermission("sys:config:update")
    public R<Void> testAiConnection() {
        // TODO: 实际测试LLM连接
        return success();
    }
}
