package com.fitness.admin.ai.service;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fitness.admin.ai.entity.KnowledgeBase;
import com.fitness.admin.ai.job.ImportJobProgress;
import com.fitness.admin.ai.job.ImportJobTracker;
import com.fitness.admin.ai.mapper.KnowledgeBaseMapper;
import com.fitness.admin.ai.vo.KnowledgeExportVO;
import com.fitness.admin.ai.vo.KnowledgeImportRow;
import com.fitness.admin.common.enums.ResultCodeEnum;
import com.fitness.admin.common.exception.BizException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiKnowledgeService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final ImportJobTracker importJobTracker;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public Page<KnowledgeBase> queryPage(Integer pageNum, Integer pageSize) {
        Page<KnowledgeBase> page = new Page<>(pageNum, pageSize);
        return knowledgeBaseMapper.selectPage(page, null);
    }

    public void save(KnowledgeBase knowledgeBase) {
        if (knowledgeBase.getId() == null) {
            knowledgeBase.setVectorStatus("pending");
            knowledgeBaseMapper.insert(knowledgeBase);
        } else {
            knowledgeBaseMapper.updateById(knowledgeBase);
        }
    }

    public KnowledgeBase getDetail(Long id) {
        return knowledgeBaseMapper.selectById(id);
    }

    public void delete(Long id) {
        knowledgeBaseMapper.deleteById(id);
    }

    /**
     * 重试向量化:把 vector_status 置回 pending,清空错误信息,
     * 等 KnowledgeIndexJob 定时任务(60s 周期)消费。
     */
    public void retryVectorize(Long id) {
        KnowledgeBase exist = knowledgeBaseMapper.selectById(id);
        if (exist == null) {
            throw new BizException(ResultCodeEnum.DATA_NOT_FOUND);
        }
        KnowledgeBase update = new KnowledgeBase();
        update.setId(id);
        update.setVectorStatus("pending");
        update.setVectorError(null);
        knowledgeBaseMapper.updateById(update);
    }

    /**
     * 批量导入(JSON / CSV / XLSX):
     * 1. 根据扩展名解析文件为 List<KnowledgeImportRow>
     * 2. 校验必填(分类 + 标题 + 内容)
     * 3. 批量插入 knowledge_base(vector_status='pending')
     * 4. 创建导入任务进度,返回 jobId,KnowledgeIndexJob 会异步消费
     */
    public String batchImport(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCodeEnum.PARAM_ERROR, "文件为空");
        }
        String name = file.getOriginalFilename() == null ? "import" : file.getOriginalFilename();
        String lower = name.toLowerCase();

        List<KnowledgeImportRow> rows;
        try {
            if (lower.endsWith(".json")) {
                rows = parseJson(file);
            } else if (lower.endsWith(".csv")) {
                rows = parseCsv(file);
            } else if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
                rows = parseExcel(file);
            } else {
                throw new BizException(ResultCodeEnum.PARAM_ERROR,
                        "仅支持 .json / .csv / .xlsx / .xls 文件");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("导入解析失败: file={}, err={}", name, e.getMessage(), e);
            throw new BizException(ResultCodeEnum.PARAM_ERROR, "文件解析失败: " + e.getMessage());
        }

        if (rows.isEmpty()) {
            throw new BizException(ResultCodeEnum.PARAM_ERROR, "文件中无有效数据行");
        }

        // 创建任务(状态 PARSING)
        String jobId = importJobTracker.createJob(name);

        List<Long> insertedIds = new ArrayList<>(rows.size());
        int validCount = 0;
        try {
            for (KnowledgeImportRow row : rows) {
                if (row == null || isBlank(row.getTitle()) || isBlank(row.getContent())
                        || isBlank(row.getCategory())) {
                    continue;
                }
                KnowledgeBase kb = new KnowledgeBase();
                kb.setCategory(row.getCategory().trim().toLowerCase());
                kb.setTitle(row.getTitle().trim());
                kb.setContent(row.getContent());
                kb.setTags(row.parseTags());
                kb.setSource(row.getSource());
                kb.setVectorStatus("pending");
                knowledgeBaseMapper.insert(kb);
                if (kb.getId() != null) {
                    insertedIds.add(kb.getId());
                    validCount++;
                }
            }
        } catch (Exception e) {
            log.error("批量导入写库失败: jobId={}, err={}", jobId, e.getMessage(), e);
            importJobTracker.markFailed(jobId, "写库失败: " + e.getMessage());
            throw new BizException("批量导入写库失败: " + e.getMessage());
        }

        if (validCount == 0) {
            importJobTracker.markFailed(jobId, "所有行都缺少必填字段(分类/标题/内容)");
            throw new BizException(ResultCodeEnum.PARAM_ERROR, "所有行都缺少必填字段(分类/标题/内容)");
        }

        importJobTracker.markIndexing(jobId, validCount, insertedIds);
        log.info("批量导入完成: jobId={}, file={}, total={}", jobId, name, validCount);
        return jobId;
    }

    public ImportJobProgress getJobProgress(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            throw new BizException(ResultCodeEnum.PARAM_ERROR, "jobId 不能为空");
        }
        ImportJobProgress p = importJobTracker.get(jobId);
        if (p == null) {
            throw new BizException(ResultCodeEnum.NOT_FOUND, "任务不存在或已过期(重启后失效)");
        }
        return p;
    }

    /**
     * 导出知识库(Excel xlsx),支持关键词/分类/状态筛选
     * 与列表筛选共用入参语义,导出条数无分页上限(由用户自负责任)
     */
    public List<KnowledgeExportVO> exportList(String keyword, String category, String vectorStatus) {
        QueryWrapper<KnowledgeBase> wrapper = Wrappers.<KnowledgeBase>query();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like("title", keyword).or().like("content", keyword));
        }
        if (category != null && !category.isBlank()) {
            wrapper.eq("category", category);
        }
        if (vectorStatus != null && !vectorStatus.isBlank()) {
            wrapper.eq("vector_status", vectorStatus);
        }
        wrapper.orderByDesc("updated_at");
        List<KnowledgeBase> rows = knowledgeBaseMapper.selectExportList(wrapper);

        List<KnowledgeExportVO> vos = new ArrayList<>(rows.size());
        for (KnowledgeBase kb : rows) {
            KnowledgeExportVO vo = new KnowledgeExportVO();
            vo.setId(kb.getId());
            vo.setCategory(kb.getCategory());
            vo.setTitle(kb.getTitle());
            vo.setContent(kb.getContent());
            vo.setTags(joinTags(kb.getTags()));
            vo.setSource(kb.getSource());
            vo.setVectorStatus(kb.getVectorStatus());
            vo.setVectorModel(kb.getVectorModel());
            vo.setVectorIndexedAt(kb.getVectorIndexedAt());
            vo.setVectorError(kb.getVectorError());
            vo.setCreatedAt(kb.getCreatedAt());
            vo.setUpdatedAt(kb.getUpdatedAt());
            vos.add(vo);
        }
        return vos;
    }

    private static String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return "";
        return tags.stream().collect(Collectors.joining(","));
    }

    // -------- 文件解析 --------

    private List<KnowledgeImportRow> parseJson(MultipartFile file) throws Exception {
        return MAPPER.readValue(file.getBytes(), new TypeReference<List<KnowledgeImportRow>>() {});
    }

    private List<KnowledgeImportRow> parseCsv(MultipartFile file) throws Exception {
        List<KnowledgeImportRow> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) return result;
            String[] cols = header.split(",", -1);
            // 去 BOM
            if (cols.length > 0 && cols[0].startsWith("﻿")) {
                cols[0] = cols[0].substring(1);
            }
            int idxCategory = indexOf(cols, "分类", "category");
            int idxTitle = indexOf(cols, "标题", "title");
            int idxContent = indexOf(cols, "内容", "content");
            int idxTags = indexOf(cols, "标签", "tags");
            int idxSource = indexOf(cols, "来源", "source");
            if (idxTitle < 0 || idxContent < 0 || idxCategory < 0) {
                throw new IllegalArgumentException("CSV 表头必须包含 分类、标题、内容 列");
            }
            String line;
            while ((line = reader.readLine()) != null) {
                // 简单 CSV(不含引号转义)够用;复杂场景用 hutool CsvUtil
                String[] cells = line.split(",", -1);
                KnowledgeImportRow r = new KnowledgeImportRow();
                if (idxCategory < cells.length) r.setCategory(cells[idxCategory]);
                if (idxTitle < cells.length) r.setTitle(cells[idxTitle]);
                if (idxContent < cells.length) r.setContent(cells[idxContent]);
                if (idxTags >= 0 && idxTags < cells.length) r.setTags(cells[idxTags]);
                if (idxSource >= 0 && idxSource < cells.length) r.setSource(cells[idxSource]);
                result.add(r);
            }
        }
        return result;
    }

    private List<KnowledgeImportRow> parseExcel(MultipartFile file) throws Exception {
        List<KnowledgeImportRow> result = new ArrayList<>();
        EasyExcel.read(file.getInputStream(), KnowledgeImportRow.class,
                new com.alibaba.excel.read.listener.ReadListener<KnowledgeImportRow>() {
                    @Override
                    public void invoke(KnowledgeImportRow data, com.alibaba.excel.context.AnalysisContext ctx) {
                        result.add(data);
                    }
                    @Override
                    public void doAfterAllAnalysed(com.alibaba.excel.context.AnalysisContext ctx) {
                        // no-op
                    }
                }).sheet().doRead();
        return result;
    }

    private static int indexOf(String[] cols, String... candidates) {
        for (int i = 0; i < cols.length; i++) {
            String c = cols[i].trim();
            for (String cand : candidates) {
                if (cand.equalsIgnoreCase(c)) return i;
            }
        }
        return -1;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 静态工具方法,避免额外 import 列表 */
    @SuppressWarnings("unused")
    private static String join(List<String> list) {
        return list == null ? "" : list.stream().collect(Collectors.joining(","));
    }

    @SuppressWarnings("unused")
    private static List<String> split(String s) {
        if (s == null || s.isBlank()) return new ArrayList<>();
        return Arrays.stream(s.split("[,，、;；\\s]+"))
                .map(String::trim).filter(x -> !x.isEmpty())
                .collect(Collectors.toList());
    }
}
