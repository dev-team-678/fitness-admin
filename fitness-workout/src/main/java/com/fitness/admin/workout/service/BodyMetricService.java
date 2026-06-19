package com.fitness.admin.workout.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fitness.admin.common.exception.BizException;
import com.fitness.admin.workout.dto.BodyMetricQueryDTO;
import com.fitness.admin.workout.entity.BodyMetric;
import com.fitness.admin.workout.mapper.BodyMetricMapper;
import com.fitness.admin.workout.vo.BodyMetricOverviewVO;
import com.fitness.admin.workout.vo.BodyMetricOverviewVO.AvgStats;
import com.fitness.admin.workout.vo.BodyMetricOverviewVO.Latest;
import com.fitness.admin.workout.vo.BodyMetricOverviewVO.TrendPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BodyMetricService {

    private final BodyMetricMapper bodyMetricMapper;

    public Page<BodyMetric> queryPage(BodyMetricQueryDTO queryDTO) {
        Page<BodyMetric> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());
        LambdaQueryWrapper<BodyMetric> wrapper = new LambdaQueryWrapper<>();

        if (queryDTO.getUserId() != null) {
            wrapper.eq(BodyMetric::getUserId, queryDTO.getUserId());
        }
        if (queryDTO.getStartDate() != null) {
            wrapper.ge(BodyMetric::getRecordDate, queryDTO.getStartDate());
        }
        if (queryDTO.getEndDate() != null) {
            wrapper.le(BodyMetric::getRecordDate, queryDTO.getEndDate());
        }
        if (Integer.valueOf(1).equals(queryDTO.getIsAbnormal())) {
            wrapper.eq(BodyMetric::getIsAbnormal, 1);
        }

        wrapper.orderByDesc(BodyMetric::getRecordDate);
        return bodyMetricMapper.selectPage(page, wrapper);
    }

    public void save(BodyMetric bodyMetric) {
        if (bodyMetric.getId() == null) {
            bodyMetricMapper.insert(bodyMetric);
        } else {
            bodyMetricMapper.updateById(bodyMetric);
        }
    }

    public void delete(Long id) {
        bodyMetricMapper.deleteById(id);
    }

    /**
     * 体测概览: 区间内记录数、用户数、异常数、最新一条、体重/BMI 趋势、平均值
     */
    public BodyMetricOverviewVO getOverview(BodyMetricQueryDTO queryDTO) {
        LambdaQueryWrapper<BodyMetric> wrapper = buildOverviewWrapper(queryDTO);
        List<BodyMetric> records = bodyMetricMapper.selectList(wrapper);

        BodyMetricOverviewVO vo = new BodyMetricOverviewVO();
        vo.setTotalRecords((long) records.size());

        if (records.isEmpty()) {
            vo.setUserCount(0L);
            vo.setAbnormalCount(0L);
            vo.setWeightTrend(Collections.emptyList());
            vo.setAvg(emptyAvg());
            return vo;
        }

        long userCount = records.stream().map(BodyMetric::getUserId).distinct().count();
        long abnormalCount = records.stream()
                .filter(r -> Integer.valueOf(1).equals(r.getIsAbnormal()))
                .count();

        // 最新一条: 记录日期最大(列表已按日期倒序)
        BodyMetric latestRec = records.get(0);
        Latest latest = new Latest();
        latest.setId(latestRec.getId());
        latest.setUserId(latestRec.getUserId());
        latest.setRecordDate(latestRec.getRecordDate());
        latest.setWeightKg(latestRec.getWeightKg());
        latest.setBodyFatPct(latestRec.getBodyFatPct());
        latest.setBmi(latestRec.getBmi());

        // 趋势: 正序取最多 60 个点
        List<TrendPoint> trend = records.stream()
                .limit(60)
                .sorted((a, b) -> a.getRecordDate().compareTo(b.getRecordDate()))
                .map(r -> {
                    TrendPoint p = new TrendPoint();
                    p.setRecordDate(r.getRecordDate());
                    p.setWeightKg(r.getWeightKg());
                    p.setBmi(r.getBmi());
                    return p;
                })
                .toList();

        AvgStats avg = new AvgStats();
        avg.setWeightKg(avg(records, BodyMetric::getWeightKg));
        avg.setBodyFatPct(avg(records, BodyMetric::getBodyFatPct));
        avg.setBmi(avg(records, BodyMetric::getBmi));

        vo.setUserCount(userCount);
        vo.setAbnormalCount(abnormalCount);
        vo.setLatest(latest);
        vo.setWeightTrend(trend);
        vo.setAvg(avg);
        return vo;
    }

    /**
     * 标记某条记录为异常(管理员操作,记录标记人/时间/备注)
     */
    public void markAbnormal(Long id, String note, Long operatorId) {
        BodyMetric existing = bodyMetricMapper.selectById(id);
        if (existing == null) {
            throw new BizException("体测记录不存在");
        }
        BodyMetric update = new BodyMetric();
        update.setId(id);
        update.setIsAbnormal(1);
        update.setAbnormalNote(note);
        update.setAbnormalMarkedAt(java.time.LocalDateTime.now());
        update.setAbnormalMarkedBy(operatorId);
        bodyMetricMapper.updateById(update);
    }

    private LambdaQueryWrapper<BodyMetric> buildOverviewWrapper(BodyMetricQueryDTO queryDTO) {
        LambdaQueryWrapper<BodyMetric> wrapper = new LambdaQueryWrapper<>();
        if (queryDTO != null) {
            if (queryDTO.getUserId() != null) {
                wrapper.eq(BodyMetric::getUserId, queryDTO.getUserId());
            }
            if (queryDTO.getStartDate() != null) {
                wrapper.ge(BodyMetric::getRecordDate, queryDTO.getStartDate());
            }
            if (queryDTO.getEndDate() != null) {
                wrapper.le(BodyMetric::getRecordDate, queryDTO.getEndDate());
            }
        }
        // 默认近 90 天,避免前端不传参时拉全表
        if (queryDTO == null || (queryDTO.getStartDate() == null && queryDTO.getEndDate() == null)) {
            LocalDate today = LocalDate.now();
            wrapper.ge(BodyMetric::getRecordDate, today.minusDays(90));
        }
        wrapper.orderByDesc(BodyMetric::getRecordDate);
        return wrapper;
    }

    private BigDecimal avg(List<BodyMetric> list, java.util.function.Function<BodyMetric, BigDecimal> mapper) {
        return list.stream()
                .map(mapper)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(Math.max(1, list.size())), 2, RoundingMode.HALF_UP);
    }

    private AvgStats emptyAvg() {
        AvgStats avg = new AvgStats();
        avg.setWeightKg(BigDecimal.ZERO);
        avg.setBodyFatPct(BigDecimal.ZERO);
        avg.setBmi(BigDecimal.ZERO);
        return avg;
    }
}