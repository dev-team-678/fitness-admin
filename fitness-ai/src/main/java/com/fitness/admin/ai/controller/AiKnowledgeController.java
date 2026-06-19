package com.fitness.admin.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fitness.admin.common.base.BaseController;
import com.fitness.admin.common.result.PageResult;
import com.fitness.admin.common.result.R;
import com.fitness.admin.ai.entity.KnowledgeBase;
import com.fitness.admin.ai.config.AiConfig;
import com.fitness.admin.ai.entity.AiChatMessage;
import com.fitness.admin.ai.rag.RagRetriever;
import com.fitness.admin.ai.service.AiAnalyticsService;
import com.fitness.admin.ai.service.AiKnowledgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "知识库管理")
@RestController
@RequestMapping("/ai-knowledge")
@RequiredArgsConstructor
@SaCheckPermission("ai:knowledge:read")
public class AiKnowledgeController extends BaseController {

    private final AiKnowledgeService aiKnowledgeService;
    private final AiAnalyticsService aiAnalyticsService;
    private final RagRetriever ragRetriever;
    private final AiConfig aiConfig;

    @Operation(summary = "知识库列表")
    @GetMapping("/list")
    public R<PageResult<KnowledgeBase>> list(@RequestParam(defaultValue = "1") Integer pageNum,
                                             @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<KnowledgeBase> page = aiKnowledgeService.queryPage(pageNum, pageSize);
        return page(page);
    }

    @Operation(summary = "知识详情")
    @GetMapping("/{id}")
    public R<KnowledgeBase> detail(@PathVariable Long id) {
        return R.ok(aiKnowledgeService.getDetail(id));
    }

    @Operation(summary = "获取知识分类")
    @GetMapping("/categories")
    public R<List<Map<String, Object>>> categories() {
        String[][] data = {
                {"1", "训练相关", "training"},
                {"2", "恢复相关", "recovery"},
                {"3", "营养相关", "nutrition"},
                {"4", "伤病相关", "injury"},
                {"5", "睡眠相关", "sleep"},
        };
        List<Map<String, Object>> list = new ArrayList<>();
        for (String[] item : data) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", Integer.parseInt(item[0]));
            map.put("name", item[1]);
            map.put("code", item[2]);
            list.add(map);
        }
        return success(list);
    }

    @Operation(summary = "新增知识")
    @PostMapping
    @SaCheckPermission("ai:knowledge:create")
    public R<Void> save(@RequestBody KnowledgeBase knowledgeBase) {
        aiKnowledgeService.save(knowledgeBase);
        return success();
    }

    @Operation(summary = "更新知识")
    @PutMapping("/{id}")
    @SaCheckPermission("ai:knowledge:update")
    public R<Void> update(@PathVariable Long id, @RequestBody KnowledgeBase knowledgeBase) {
        knowledgeBase.setId(id);
        aiKnowledgeService.save(knowledgeBase);
        return success();
    }

    @Operation(summary = "删除知识")
    @DeleteMapping("/{id}")
    @SaCheckPermission("ai:knowledge:delete")
    public R<Void> delete(@PathVariable Long id) {
        aiKnowledgeService.delete(id);
        return success();
    }

    @Operation(summary = "知识使用统计")
    @GetMapping("/analytics/usage")
    public R<List<Map<String, Object>>> usage() {
        return R.ok(aiAnalyticsService.getKnowledgeUsage());
    }

    @Operation(summary = "RAG命中率")
    @GetMapping("/analytics/rag-hit-rate")
    public R<List<Map<String, Object>>> ragHitRate() {
        return R.ok(aiAnalyticsService.getRagHitRate());
    }

    @Operation(summary = "RAG检索测试")
    @PostMapping("/rag-test")
    public R<Map<String, Object>> ragTest(@RequestBody Map<String, Object> params) {
        String query = (String) params.get("query");
        Integer topK = params.get("topK") != null ? (Integer) params.get("topK") : aiConfig.getRagTopK();
        if (topK == null || topK <= 0) topK = 5;
        Double minScore = aiConfig.getRagMinScore() != null ? aiConfig.getRagMinScore() : 0.6;

        List<AiChatMessage.RagReference> refs = ragRetriever.retrieve(query, topK, minScore);
        List<Map<String, Object>> results = new ArrayList<>();
        for (AiChatMessage.RagReference ref : refs) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", ref.getId());
            item.put("title", ref.getTitle());
            item.put("category", ref.getCategoryName());
            item.put("score", ref.getScore());
            item.put("source", ref.getSource());
            results.add(item);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("query", query);
        data.put("results", results);
        data.put("hitCount", results.size());
        data.put("message", "RAG检索测试完成");
        return success(data);
    }
}
