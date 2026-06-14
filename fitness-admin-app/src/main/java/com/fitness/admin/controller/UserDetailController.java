package com.fitness.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fitness.admin.ai.entity.AiChatSession;
import com.fitness.admin.ai.mapper.AiChatSessionMapper;
import com.fitness.admin.common.base.BaseController;
import com.fitness.admin.common.result.PageResult;
import com.fitness.admin.common.result.R;
import com.fitness.admin.content.entity.WorkoutPlan;
import com.fitness.admin.content.mapper.PlanMapper;
import com.fitness.admin.user.entity.UserFitnessProfile;
import com.fitness.admin.user.mapper.UserAchievementQueryMapper;
import com.fitness.admin.user.mapper.UserFitnessProfileMapper;
import com.fitness.admin.workout.entity.BodyMetric;
import com.fitness.admin.workout.entity.WorkoutLog;
import com.fitness.admin.workout.mapper.BodyMetricMapper;
import com.fitness.admin.workout.mapper.WorkoutLogMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Tag(name = "用户详情")
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
@SaCheckPermission("user:read")
public class UserDetailController extends BaseController {

    private final WorkoutLogMapper workoutLogMapper;
    private final UserAchievementQueryMapper userAchievementQueryMapper;
    private final UserFitnessProfileMapper userFitnessProfileMapper;
    private final AiChatSessionMapper aiChatSessionMapper;
    private final PlanMapper planMapper;
    private final BodyMetricMapper bodyMetricMapper;

    @Operation(summary = "用户训练记录")
    @GetMapping("/{id}/workouts")
    public R<PageResult<Map<String, Object>>> userWorkouts(@PathVariable Long id,
                                                            @RequestParam(defaultValue = "1") Integer pageNum,
                                                            @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<WorkoutLog> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<WorkoutLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkoutLog::getUserId, id).orderByDesc(WorkoutLog::getWorkoutDate);
        Page<WorkoutLog> result = workoutLogMapper.selectPage(page, wrapper);

        // Batch query plan names
        Set<Long> planIds = result.getRecords().stream()
                .map(WorkoutLog::getPlanId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> planNameMap = new HashMap<>();
        if (!planIds.isEmpty()) {
            List<WorkoutPlan> plans = planMapper.selectBatchIds(planIds);
            for (WorkoutPlan plan : plans) {
                planNameMap.put(plan.getId(), plan.getName());
            }
        }

        // Enrich records with planName
        List<Map<String, Object>> enrichedRecords = new ArrayList<>();
        for (WorkoutLog log : result.getRecords()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", log.getId());
            map.put("planId", log.getPlanId());
            map.put("planDayId", log.getPlanDayId());
            map.put("planName", planNameMap.getOrDefault(log.getPlanId(), "自由训练"));
            map.put("workoutDate", log.getWorkoutDate());
            map.put("startTime", log.getStartTime());
            map.put("durationMin", log.getDurationMin());
            map.put("totalVolumeKg", log.getTotalVolumeKg());
            map.put("totalSets", log.getTotalSets());
            map.put("estimatedCalories", log.getEstimatedCalories());
            map.put("status", log.getStatus());
            enrichedRecords.add(map);
        }

        PageResult<Map<String, Object>> pageResult = new PageResult<>();
        pageResult.setList(enrichedRecords);
        pageResult.setTotal(result.getTotal());
        pageResult.setPageNum(result.getCurrent());
        pageResult.setPageSize(result.getSize());
        pageResult.setPages(result.getPages());
        return success(pageResult);
    }

    @Operation(summary = "用户身体数据")
    @GetMapping("/{id}/body-metrics")
    public R<List<BodyMetric>> userBodyMetrics(@PathVariable Long id) {
        LambdaQueryWrapper<BodyMetric> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BodyMetric::getUserId, id).orderByAsc(BodyMetric::getRecordDate);
        return success(bodyMetricMapper.selectList(wrapper));
    }

    @Operation(summary = "用户成就列表")
    @GetMapping("/{id}/achievements")
    public R<List<Map<String, Object>>> userAchievements(@PathVariable Long id) {
        return success(userAchievementQueryMapper.selectByUserId(id));
    }

    @Operation(summary = "用户AI健身档案")
    @GetMapping("/{id}/ai-profile")
    public R<UserFitnessProfile> userAiProfile(@PathVariable Long id) {
        LambdaQueryWrapper<UserFitnessProfile> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserFitnessProfile::getUserId, id).last("LIMIT 1");
        return success(userFitnessProfileMapper.selectOne(wrapper));
    }

    @Operation(summary = "用户AI对话记录")
    @GetMapping("/{id}/ai-chats")
    public R<PageResult<AiChatSession>> userAiChats(@PathVariable Long id,
                                                      @RequestParam(defaultValue = "1") Integer pageNum,
                                                      @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<AiChatSession> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<AiChatSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiChatSession::getUserId, id).orderByDesc(AiChatSession::getCreatedAt);
        return page(aiChatSessionMapper.selectPage(page, wrapper));
    }
}
