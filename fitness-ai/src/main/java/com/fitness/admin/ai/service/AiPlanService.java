package com.fitness.admin.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fitness.admin.ai.config.AiConfig;
import com.fitness.admin.ai.entity.AiAdjustmentConfig;
import com.fitness.admin.ai.entity.AiPlan;
import com.fitness.admin.ai.entity.PlanLoadAdjustment;
import com.fitness.admin.ai.mapper.AiAdjustmentConfigMapper;
import com.fitness.admin.ai.mapper.AiPlanMapper;
import com.fitness.admin.ai.mapper.PlanLoadAdjustmentMapper;
import com.fitness.admin.common.enums.ResultCodeEnum;
import com.fitness.admin.common.exception.BizException;
import com.fitness.admin.common.utils.SecurityUtil;
import com.fitness.admin.content.entity.WorkoutPlan;
import com.fitness.admin.content.mapper.PlanMapper;
import com.fitness.admin.user.entity.User;
import com.fitness.admin.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiPlanService {

    private final AiPlanMapper aiPlanMapper;
    private final PlanMapper planMapper;
    private final PlanLoadAdjustmentMapper planLoadAdjustmentMapper;
    private final AiAdjustmentConfigMapper aiAdjustmentConfigMapper;
    private final UserMapper userMapper;
    private final AiConfig aiConfig;

    public Page<AiPlan> queryPage(Integer pageNum, Integer pageSize, Long userId, String status, String splitType) {
        Page<AiPlan> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<AiPlan> wrapper = new LambdaQueryWrapper<>();
        if (userId != null) {
            wrapper.eq(AiPlan::getUserId, userId);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(AiPlan::getStatus, status);
        }
        if (splitType != null && !splitType.isEmpty()) {
            wrapper.eq(AiPlan::getSplitType, splitType);
        }
        wrapper.orderByDesc(AiPlan::getCreatedAt);
        return aiPlanMapper.selectPage(page, wrapper);
    }

    public void updateStatus(Long id, String status) {
        AiPlan plan = new AiPlan();
        plan.setId(id);
        plan.setStatus(status);
        aiPlanMapper.updateById(plan);
    }

    @Transactional
    public Long convertToSystem(Long id) {
        AiPlan aiPlan = aiPlanMapper.selectById(id);
        if (aiPlan == null) {
            throw new RuntimeException("AI计划不存在");
        }

        WorkoutPlan workoutPlan = new WorkoutPlan();
        workoutPlan.setName("AI生成-" + (aiPlan.getGoal() != null ? aiPlan.getGoal() : "训练计划"));
        workoutPlan.setDescription(aiPlan.getExplanation());
        workoutPlan.setFitnessGoal(aiPlan.getGoal());
        workoutPlan.setDaysPerWeek(aiPlan.getDaysPerWeek());
        workoutPlan.setDurationWeeks(4);
        workoutPlan.setDifficultyLevel("intermediate");
        workoutPlan.setIsSystem(1);
        workoutPlan.setAiGenerated(1);
        workoutPlan.setAiGenerationParams(aiPlan.getGenerationParams());
        workoutPlan.setStatus(1);
        planMapper.insert(workoutPlan);

        AiPlan update = new AiPlan();
        update.setId(id);
        update.setConverted(1);
        update.setConvertedPlanId(workoutPlan.getId());
        update.setStatus("confirmed");
        aiPlanMapper.updateById(update);

        // 更新用户的当前活跃计划
        Long userId = aiPlan.getUserId();
        if (userId != null) {
            User user = userMapper.selectById(userId);
            if (user != null) {
                user.setCurrentPlanId(workoutPlan.getId());
                userMapper.updateById(user);
            }
        }

        return workoutPlan.getId();
    }

    public AiPlan getDetail(Long id) {
        return aiPlanMapper.selectById(id);
    }

    public List<PlanLoadAdjustment> getAdjustments(Long aiPlanId) {
        return planLoadAdjustmentMapper.selectList(
                new LambdaQueryWrapper<PlanLoadAdjustment>()
                        .eq(PlanLoadAdjustment::getAiPlanId, aiPlanId)
                        .orderByAsc(PlanLoadAdjustment::getWeekNumber));
    }

    public List<AiAdjustmentConfig> getAdjustmentRules() {
        return aiAdjustmentConfigMapper.selectList(null);
    }

    public void updateAdjustmentConfig(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return;
        }

        // 双校验 1:登录用户态 + 角色 = admin
        // (Sa-Token 已保证 controller 入口有 ai:plan:update 权限,但 service 层仍做一次防御性校验,
        //  防止内部调用绕过权限注解)
        Long currentUserId = SecurityUtil.getCurrentUserId();
        if (currentUserId == null) {
            throw new BizException(ResultCodeEnum.UNAUTHORIZED);
        }
        if (!SecurityUtil.hasRole("admin")) {
            log.warn("非管理员尝试修改微调规则: userId={}", currentUserId);
            throw new BizException(ResultCodeEnum.FORBIDDEN);
        }

        // 双校验 2:key 白名单 — 仅 Nacos 配的 whitelist 内的 key 可写入,
        // 避免前端任意传入键污染 ai_adjustment_config。
        List<String> whitelist = aiConfig.getAdjustmentRuleWhitelist();
        Set<String> validKeys = (whitelist == null || whitelist.isEmpty())
                ? Set.of()
                : new HashSet<>(whitelist);

        // 获取所有已有的合法config_key(数据库里已存在的 key 视为合法,便于历史数据平滑)
        Set<String> existingKeys = new HashSet<>();
        for (AiAdjustmentConfig c : aiAdjustmentConfigMapper.selectList(null)) {
            existingKeys.add(c.getConfigKey());
        }
        Set<String> allowedKeys = new HashSet<>(existingKeys);
        allowedKeys.addAll(validKeys);

        for (Map.Entry<String, Object> entry : config.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // 跳过非字符串值的复杂对象
            if (value == null || value instanceof Map || value instanceof List) {
                continue;
            }

            String strValue = String.valueOf(value);
            // 限制单值长度不超过255
            if (strValue.length() > 255) {
                continue;
            }

            // 白名单校验:既不在白名单也不在已有 key 集合 → 拒绝写入
            if (!allowedKeys.contains(key)) {
                log.warn("拒绝写入未授权的微调规则 key: userId={}, key={}", currentUserId, key);
                continue;
            }

            if (existingKeys.contains(key)) {
                // 更新已有配置
                LambdaQueryWrapper<AiAdjustmentConfig> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(AiAdjustmentConfig::getConfigKey, key);
                AiAdjustmentConfig existing = aiAdjustmentConfigMapper.selectOne(wrapper);
                if (existing != null) {
                    existing.setConfigValue(strValue);
                    aiAdjustmentConfigMapper.updateById(existing);
                }
            } else {
                // 新增配置(必经过白名单,这里 key 已在 allowedKeys)
                AiAdjustmentConfig newConfig = new AiAdjustmentConfig();
                newConfig.setConfigKey(key);
                newConfig.setConfigValue(strValue);
                aiAdjustmentConfigMapper.insert(newConfig);
            }
        }
    }
}
