package com.fitness.admin.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fitness.admin.ai.entity.*;
import com.fitness.admin.ai.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AiAnalyticsService {

    private final AiChatSessionMapper aiChatSessionMapper;
    private final AiChatMessageMapper aiChatMessageMapper;
    private final AiPlanMapper aiPlanMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final AiUsageDailyMapper aiUsageDailyMapper;

    public Map<String, Object> getOverview() {
        Map<String, Object> result = new HashMap<>();
        long sessionCount = aiChatSessionMapper.selectCount(null);
        long messageCount = aiChatMessageMapper.selectCount(null);
        long thumbsUp = aiChatMessageMapper.selectCount(
                new LambdaQueryWrapper<AiChatMessage>().eq(AiChatMessage::getFeedback, 1));
        long thumbsDown = aiChatMessageMapper.selectCount(
                new LambdaQueryWrapper<AiChatMessage>().eq(AiChatMessage::getFeedback, -1));
        long plansGenerated = aiPlanMapper.selectCount(null);
        Long tokenUsage = aiChatMessageMapper.selectCount(null) * 200L;

        long feedbackTotal = thumbsUp + thumbsDown;
        int satisfactionRate = feedbackTotal > 0
                ? BigDecimal.valueOf(thumbsUp)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(feedbackTotal), 0, RoundingMode.HALF_UP)
                    .intValue()
                : 0;

        result.put("sessionCount", sessionCount);
        result.put("messageCount", messageCount);
        result.put("satisfactionRate", satisfactionRate);
        result.put("plansGenerated", plansGenerated);
        result.put("tokenUsage", tokenUsage);
        return result;
    }

    public List<Map<String, Object>> getChatTrend() {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(29);
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.atTime(LocalTime.MAX);

        List<AiChatMessage> messages = aiChatMessageMapper.selectList(
                new LambdaQueryWrapper<AiChatMessage>()
                        .between(AiChatMessage::getCreatedAt, start, end));

        Map<LocalDate, Long> grouped = messages.stream()
                .filter(m -> m.getCreatedAt() != null)
                .collect(Collectors.groupingBy(
                        m -> m.getCreatedAt().toLocalDate(),
                        Collectors.counting()));

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");
        List<Map<String, Object>> result = new ArrayList<>();
        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            Map<String, Object> point = new HashMap<>();
            point.put("date", d.format(fmt));
            point.put("count", grouped.getOrDefault(d, 0L));
            result.add(point);
        }
        return result;
    }

    public List<Map<String, Object>> getSatisfactionTrend() {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(29);
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.atTime(LocalTime.MAX);

        List<AiChatMessage> messages = aiChatMessageMapper.selectList(
                new LambdaQueryWrapper<AiChatMessage>()
                        .between(AiChatMessage::getCreatedAt, start, end)
                        .isNotNull(AiChatMessage::getFeedback));

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");
        Map<LocalDate, Map<String, Long>> grouped = new LinkedHashMap<>();
        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            grouped.put(d, new HashMap<>());
        }

        for (AiChatMessage msg : messages) {
            LocalDate date = msg.getCreatedAt().toLocalDate();
            Map<String, Long> dayMap = grouped.get(date);
            if (dayMap == null) continue;
            String key;
            if (msg.getFeedback() != null && msg.getFeedback() > 0) {
                key = "positive";
            } else if (msg.getFeedback() != null && msg.getFeedback() < 0) {
                key = "negative";
            } else {
                key = "neutral";
            }
            dayMap.merge(key, 1L, Long::sum);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<LocalDate, Map<String, Long>> entry : grouped.entrySet()) {
            Map<String, Object> point = new HashMap<>();
            point.put("date", entry.getKey().format(fmt));
            point.put("positive", entry.getValue().getOrDefault("positive", 0L));
            point.put("neutral", entry.getValue().getOrDefault("neutral", 0L));
            point.put("negative", entry.getValue().getOrDefault("negative", 0L));
            result.add(point);
        }
        return result;
    }

    public List<Map<String, Object>> getHotQuestions() {
        List<AiChatMessage> userMessages = aiChatMessageMapper.selectList(
                new LambdaQueryWrapper<AiChatMessage>()
                        .eq(AiChatMessage::getRole, "user")
                        .orderByDesc(AiChatMessage::getCreatedAt)
                        .last("LIMIT 200"));

        Map<String, Long> grouped = userMessages.stream()
                .filter(m -> m.getContent() != null && m.getContent().length() >= 4)
                .collect(Collectors.groupingBy(
                        m -> m.getContent().length() > 50
                                ? m.getContent().substring(0, 50) + "..."
                                : m.getContent(),
                        Collectors.counting()));

        return grouped.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .map(entry -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("question", entry.getKey());
                    item.put("count", entry.getValue());
                    item.put("rate", 80);
                    return item;
                })
                .collect(Collectors.toList());
    }

    public List<Map<String, Object>> getKnowledgeUsage() {
        List<KnowledgeBase> items = knowledgeBaseMapper.selectList(
                new LambdaQueryWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getStatus, 1)
                        .orderByDesc(KnowledgeBase::getCreatedAt)
                        .last("LIMIT 10"));

        return items.stream().map(item -> {
            Map<String, Object> map = new HashMap<>();
            map.put("title", item.getTitle());
            map.put("recallCount", 0);
            map.put("avgScore", 0);
            return map;
        }).collect(Collectors.toList());
    }

    public Map<String, Object> getPlanStats() {
        Map<String, Object> result = new HashMap<>();
        long total = aiPlanMapper.selectCount(null);
        long converted = aiPlanMapper.selectCount(
                new LambdaQueryWrapper<AiPlan>().eq(AiPlan::getConverted, 1));
        result.put("total", total);
        result.put("converted", converted);
        result.put("conversionRate", total > 0
                ? BigDecimal.valueOf(converted).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        return result;
    }

    public List<Map<String, Object>> getTokenUsage() {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(29);
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.atTime(LocalTime.MAX);

        List<AiChatMessage> messages = aiChatMessageMapper.selectList(
                new LambdaQueryWrapper<AiChatMessage>()
                        .between(AiChatMessage::getCreatedAt, start, end));

        Map<LocalDate, Long> grouped = messages.stream()
                .filter(m -> m.getCreatedAt() != null && m.getTokenCount() != null)
                .collect(Collectors.groupingBy(
                        m -> m.getCreatedAt().toLocalDate(),
                        Collectors.summingLong(AiChatMessage::getTokenCount)));

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");
        List<Map<String, Object>> result = new ArrayList<>();
        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            Map<String, Object> point = new HashMap<>();
            point.put("date", d.format(fmt));
            point.put("tokens", grouped.getOrDefault(d, 0L));
            result.add(point);
        }
        return result;
    }

    public List<Map<String, Object>> getRagHitRate() {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(6);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");

        Map<LocalDate, BigDecimal> daily = new HashMap<>();
        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            daily.put(d, BigDecimal.ZERO);
        }
        List<AiUsageDaily> records = aiUsageDailyMapper.selectList(
                new LambdaQueryWrapper<AiUsageDaily>()
                        .between(AiUsageDaily::getStatDate, startDate, endDate));
        for (AiUsageDaily r : records) {
            if (r.getStatDate() != null) {
                daily.put(r.getStatDate(), r.getRagHitRate() == null ? BigDecimal.ZERO : r.getRagHitRate());
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<LocalDate, BigDecimal> entry : daily.entrySet()) {
            Map<String, Object> point = new HashMap<>();
            point.put("date", entry.getKey().format(fmt));
            point.put("hitRate", entry.getValue());
            result.add(point);
        }
        return result;
    }

    public List<Map<String, Object>> getResponseTime() {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(29);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");
        List<Map<String, Object>> result = new ArrayList<>();
        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            Map<String, Object> point = new HashMap<>();
            point.put("date", d.format(fmt));
            point.put("avgMs", 0);
            point.put("p95Ms", 0);
            result.add(point);
        }
        return result;
    }
}
