package com.fitness.admin.workout.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fitness.admin.workout.dto.AnalyticsQueryDTO;
import com.fitness.admin.workout.dto.WorkoutQueryDTO;
import com.fitness.admin.workout.entity.WorkoutLog;
import com.fitness.admin.workout.entity.WorkoutLogExercise;
import com.fitness.admin.workout.mapper.WorkoutLogExerciseMapper;
import com.fitness.admin.content.entity.Exercise;
import com.fitness.admin.content.mapper.ExerciseMapper;
import com.fitness.admin.workout.mapper.WorkoutLogMapper;
import com.fitness.admin.workout.vo.ExercisePopularityVO;
import com.fitness.admin.workout.vo.PlanFunnelVO;
import com.fitness.admin.workout.vo.WorkoutLogExportVO;
import com.fitness.admin.workout.vo.WorkoutOverviewVO;
import com.fitness.admin.workout.vo.WorkoutPeakHoursVO;
import com.fitness.admin.workout.vo.WorkoutTrendVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkoutRecordService {

    private final WorkoutLogMapper workoutLogMapper;
    private final WorkoutLogExerciseMapper workoutLogExerciseMapper;
    private final ExerciseMapper exerciseMapper;

    public Page<WorkoutLog> queryPage(WorkoutQueryDTO queryDTO) {
        Page<WorkoutLog> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());
        LambdaQueryWrapper<WorkoutLog> wrapper = new LambdaQueryWrapper<>();

        if (queryDTO.getUserId() != null) {
            wrapper.eq(WorkoutLog::getUserId, queryDTO.getUserId());
        }
        if (queryDTO.getStartDate() != null) {
            wrapper.ge(WorkoutLog::getWorkoutDate, queryDTO.getStartDate());
        }
        if (queryDTO.getEndDate() != null) {
            wrapper.le(WorkoutLog::getWorkoutDate, queryDTO.getEndDate());
        }

        wrapper.orderByDesc(WorkoutLog::getWorkoutDate);
        return workoutLogMapper.selectPage(page, wrapper);
    }

    /**
     * 导出训练记录（不分页，最多导出 5000 条）。
     */
    public List<WorkoutLogExportVO> queryExportList(WorkoutQueryDTO queryDTO) {
        LambdaQueryWrapper<WorkoutLog> wrapper = new LambdaQueryWrapper<>();
        if (queryDTO.getUserId() != null) {
            wrapper.eq(WorkoutLog::getUserId, queryDTO.getUserId());
        }
        if (queryDTO.getStartDate() != null) {
            wrapper.ge(WorkoutLog::getWorkoutDate, queryDTO.getStartDate());
        }
        if (queryDTO.getEndDate() != null) {
            wrapper.le(WorkoutLog::getWorkoutDate, queryDTO.getEndDate());
        }
        wrapper.orderByDesc(WorkoutLog::getWorkoutDate).last("LIMIT 5000");
        List<WorkoutLog> logs = workoutLogMapper.selectList(wrapper);

        List<WorkoutLogExportVO> result = new ArrayList<>();
        for (WorkoutLog log : logs) {
            WorkoutLogExportVO vo = new WorkoutLogExportVO();
            vo.setId(log.getId());
            vo.setUserId(log.getUserId());
            vo.setWorkoutDate(log.getWorkoutDate());
            vo.setStartTime(log.getStartTime());
            vo.setEndTime(log.getEndTime());
            vo.setDurationMin(log.getDurationMin());
            vo.setTotalVolumeKg(log.getTotalVolumeKg());
            vo.setTotalSets(log.getTotalSets());
            vo.setEstimatedCalories(log.getEstimatedCalories());
            vo.setStatus(statusLabel(log.getStatus()));
            vo.setNotes(log.getNotes());
            vo.setFeelingScore(log.getFeelingScore());
            vo.setRpe(log.getRpe());
            result.add(vo);
        }
        return result;
    }

    private static String statusLabel(String status) {
        if (status == null) return "未知";
        return switch (status) {
            case "completed" -> "已完成";
            case "in_progress" -> "进行中";
            case "cancelled" -> "已取消";
            default -> status;
        };
    }

    public WorkoutOverviewVO getOverview(AnalyticsQueryDTO queryDTO) {
        LambdaQueryWrapper<WorkoutLog> wrapper = buildDateWrapper(queryDTO);
        List<WorkoutLog> logs = workoutLogMapper.selectList(wrapper);

        WorkoutOverviewVO vo = new WorkoutOverviewVO();
        vo.setTotalWorkouts((long) logs.size());

        if (logs.isEmpty()) {
            vo.setCompletionRate(BigDecimal.ZERO);
            vo.setAvgDuration(0);
            vo.setTotalVolume(BigDecimal.ZERO);
            return vo;
        }

        long completedCount = logs.stream()
                .filter(log -> "completed".equals(log.getStatus()))
                .count();
        vo.setCompletionRate(BigDecimal.valueOf(completedCount)
                .divide(BigDecimal.valueOf(logs.size()), 4, RoundingMode.HALF_UP));

        double avgDuration = logs.stream()
                .filter(log -> log.getDurationMin() != null)
                .mapToInt(WorkoutLog::getDurationMin)
                .average()
                .orElse(0);
        vo.setAvgDuration((int) Math.round(avgDuration));

        BigDecimal totalVolume = logs.stream()
                .filter(log -> log.getTotalVolumeKg() != null)
                .map(WorkoutLog::getTotalVolumeKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        vo.setTotalVolume(totalVolume);

        return vo;
    }

    public WorkoutTrendVO getTrend(AnalyticsQueryDTO queryDTO) {
        LocalDate endDate = queryDTO.getEndDate() != null ? queryDTO.getEndDate() : LocalDate.now();
        LocalDate startDate = queryDTO.getStartDate() != null ? queryDTO.getStartDate() : endDate.minusDays(29);

        LambdaQueryWrapper<WorkoutLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.ge(WorkoutLog::getWorkoutDate, startDate)
                .le(WorkoutLog::getWorkoutDate, endDate)
                .orderByAsc(WorkoutLog::getWorkoutDate);
        List<WorkoutLog> logs = workoutLogMapper.selectList(wrapper);

        Map<LocalDate, List<WorkoutLog>> grouped = logs.stream()
                .collect(Collectors.groupingBy(WorkoutLog::getWorkoutDate));

        WorkoutTrendVO vo = new WorkoutTrendVO();
        List<String> dates = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        List<Long> volumes = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");

        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            dates.add(d.format(fmt));
            List<WorkoutLog> dayLogs = grouped.getOrDefault(d, Collections.emptyList());
            counts.add(dayLogs.size());
            long dayVolume = dayLogs.stream()
                    .filter(log -> log.getTotalVolumeKg() != null)
                    .map(WorkoutLog::getTotalVolumeKg)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .longValue();
            volumes.add(dayVolume);
        }

        vo.setDates(dates);
        vo.setCounts(counts);
        vo.setVolumes(volumes);
        return vo;
    }

    public WorkoutPeakHoursVO getPeakHours(AnalyticsQueryDTO queryDTO) {
        LocalDate endDate = queryDTO.getEndDate() != null ? queryDTO.getEndDate() : LocalDate.now();
        LocalDate startDate = queryDTO.getStartDate() != null ? queryDTO.getStartDate() : endDate.minusWeeks(1);

        LambdaQueryWrapper<WorkoutLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.ge(WorkoutLog::getWorkoutDate, startDate)
                .le(WorkoutLog::getWorkoutDate, endDate)
                .isNotNull(WorkoutLog::getStartTime);
        List<WorkoutLog> logs = workoutLogMapper.selectList(wrapper);

        List<Integer> hours = new ArrayList<>();
        for (int h = 6; h <= 23; h++) {
            hours.add(h);
        }

        DayOfWeek[] weekDays = {DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY};
        String[] dayNames = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        List<String> days = Arrays.asList(dayNames);

        Map<String, Integer> heatMap = new HashMap<>();
        for (WorkoutLog log : logs) {
            LocalDateTime start = log.getStartTime();
            int hour = start.getHour();
            if (hour < 6) hour = 6;
            if (hour > 23) hour = 23;
            int dayIndex = start.getDayOfWeek().getValue() - 1;
            String key = dayIndex + "-" + hour;
            heatMap.merge(key, 1, Integer::sum);
        }

        List<int[]> values = new ArrayList<>();
        for (int di = 0; di < 7; di++) {
            for (int hi = 0; hi < hours.size(); hi++) {
                int hour = hours.get(hi);
                String key = di + "-" + hour;
                int count = heatMap.getOrDefault(key, 0);
                values.add(new int[]{hi, di, count});
            }
        }

        WorkoutPeakHoursVO vo = new WorkoutPeakHoursVO();
        vo.setHours(hours);
        vo.setDays(days);
        vo.setValues(values);
        return vo;
    }

    public List<ExercisePopularityVO> getExercisePopularity(AnalyticsQueryDTO queryDTO) {
        LocalDate endDate = queryDTO.getEndDate() != null ? queryDTO.getEndDate() : LocalDate.now();
        LocalDate startDate = queryDTO.getStartDate() != null ? queryDTO.getStartDate() : endDate.minusDays(29);

        LambdaQueryWrapper<WorkoutLog> logWrapper = new LambdaQueryWrapper<>();
        logWrapper.ge(WorkoutLog::getWorkoutDate, startDate)
                .le(WorkoutLog::getWorkoutDate, endDate);
        List<WorkoutLog> logs = workoutLogMapper.selectList(logWrapper);

        List<Long> logIds = logs.stream().map(WorkoutLog::getId).collect(Collectors.toList());
        if (logIds.isEmpty()) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<WorkoutLogExercise> exWrapper = new LambdaQueryWrapper<>();
        exWrapper.in(WorkoutLogExercise::getWorkoutLogId, logIds);
        List<WorkoutLogExercise> exercises = workoutLogExerciseMapper.selectList(exWrapper);

        Map<Long, List<WorkoutLogExercise>> grouped = exercises.stream()
                .filter(e -> e.getExerciseId() != null)
                .collect(Collectors.groupingBy(WorkoutLogExercise::getExerciseId));

        Map<Long, String> exerciseNameMap = new HashMap<>();
        if (!grouped.isEmpty()) {
            List<Exercise> exerciseList = exerciseMapper.selectBatchIds(grouped.keySet());
            for (Exercise ex : exerciseList) {
                exerciseNameMap.put(ex.getId(), ex.getName());
            }
        }

        Map<Long, BigDecimal> logVolumeMap = logs.stream()
                .filter(l -> l.getTotalVolumeKg() != null)
                .collect(Collectors.toMap(WorkoutLog::getId, WorkoutLog::getTotalVolumeKg, (a, b) -> a));

        Map<Long, Long> logUserMap = logs.stream()
                .collect(Collectors.toMap(WorkoutLog::getId, WorkoutLog::getUserId, (a, b) -> a));

        List<ExercisePopularityVO> result = new ArrayList<>();
        for (Map.Entry<Long, List<WorkoutLogExercise>> entry : grouped.entrySet()) {
            ExercisePopularityVO vo = new ExercisePopularityVO();
            vo.setName(exerciseNameMap.getOrDefault(entry.getKey(), "未知动作"));
            vo.setCount((long) entry.getValue().size());

            BigDecimal totalVol = entry.getValue().stream()
                    .map(e -> logVolumeMap.getOrDefault(e.getWorkoutLogId(), BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            vo.setTotalVolume(totalVol);

            if (!entry.getValue().isEmpty()) {
                vo.setAvgWeight(totalVol.divide(BigDecimal.valueOf(entry.getValue().size()), 2, RoundingMode.HALF_UP));
            } else {
                vo.setAvgWeight(BigDecimal.ZERO);
            }

            long userCount = entry.getValue().stream()
                    .map(e -> logUserMap.getOrDefault(e.getWorkoutLogId(), 0L))
                    .distinct()
                    .count();
            vo.setUserCount(userCount);

            result.add(vo);
        }

        result.sort(Comparator.comparingLong(ExercisePopularityVO::getCount).reversed());
        if (result.size() > 10) {
            result = result.subList(0, 10);
        }
        return result;
    }

    public PlanFunnelVO getPlanFunnel(AnalyticsQueryDTO queryDTO) {
        LocalDate endDate = queryDTO.getEndDate() != null ? queryDTO.getEndDate() : LocalDate.now();
        LocalDate startDate = queryDTO.getStartDate() != null ? queryDTO.getStartDate() : endDate.minusDays(29);

        LambdaQueryWrapper<WorkoutLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.ge(WorkoutLog::getWorkoutDate, startDate)
                .le(WorkoutLog::getWorkoutDate, endDate);
        List<WorkoutLog> logs = workoutLogMapper.selectList(wrapper);

        long totalPlans = logs.stream()
                .filter(log -> log.getPlanId() != null)
                .map(WorkoutLog::getPlanId)
                .distinct()
                .count();

        long startedPlans = totalPlans;

        long completedPlans = logs.stream()
                .filter(log -> log.getPlanId() != null && "completed".equals(log.getStatus()))
                .map(WorkoutLog::getPlanId)
                .distinct()
                .count();

        PlanFunnelVO vo = new PlanFunnelVO();
        vo.setTotalPlans(totalPlans);
        vo.setStartedPlans(startedPlans);
        vo.setCompletedPlans(completedPlans);

        if (totalPlans > 0) {
            vo.setStartRate(BigDecimal.ONE);
            vo.setCompletionRate(BigDecimal.valueOf(completedPlans)
                    .divide(BigDecimal.valueOf(totalPlans), 4, RoundingMode.HALF_UP));
        } else {
            vo.setStartRate(BigDecimal.ZERO);
            vo.setCompletionRate(BigDecimal.ZERO);
        }

        return vo;
    }

    private LambdaQueryWrapper<WorkoutLog> buildDateWrapper(AnalyticsQueryDTO queryDTO) {
        LambdaQueryWrapper<WorkoutLog> wrapper = new LambdaQueryWrapper<>();
        if (queryDTO.getStartDate() != null) {
            wrapper.ge(WorkoutLog::getWorkoutDate, queryDTO.getStartDate());
        }
        if (queryDTO.getEndDate() != null) {
            wrapper.le(WorkoutLog::getWorkoutDate, queryDTO.getEndDate());
        }
        return wrapper;
    }
}
