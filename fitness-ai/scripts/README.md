# RAG 灌库与检索调试脚本

`fitness-ai/scripts/rag_debug.py` 是一段独立 Python 脚本,用于在不启动 Spring Boot 服务的情况下,直接验证 **Embedding → Qdrant → RAG 检索** 整条链路是否通畅。

## 解决的问题

Java 后端 `KnowledgeIndexJob` 每 60 秒扫描 `knowledge_base.vector_status='pending'` 的记录,走 `EmbeddingService` → `QdrantVectorStore.upsertChunks` 把向量写入 Qdrant。当发现集合里 `points_count=0` 时,用此脚本可以快速判断是:

- Embedding 接口调用失败(认证、配额、模型名)
- 切块逻辑产出空 chunk
- Qdrant 写入失败(端口未通、维度不匹配)
- 检索 score 全部低于阈值(向量写对了但语义检索不到)

## 与 Java 端对齐

| 维度 | Java 端 | 脚本 |
|---|---|---|
| Embedding | DashScope `text-embedding-v3` | 同 |
| 向量维度 | 1024 | 同 |
| 距离度量 | Cosine | 同 |
| Qdrant 端口 | gRPC `6334` / HTTP REST `6333` | 走 HTTP REST `6333` |
| Payload 字段 | `sourceType`, `sourceId`, `chunkText`, `title`, `category` | 同 |
| 切块大小 | `AI_CHUNK_SIZE=500`,`AI_CHUNK_OVERLAP=50` | 同,Python 重写 `KnowledgeChunker.split` 语义 |
| scoreThreshold | `AI_RAG_MIN_SCORE=0.6` | 同 |

> 脚本走 Qdrant HTTP REST (`6333`),因为纯 Python `urllib` 即可,无需装 `grpcio` / `qdrant-client`;Java 端走 gRPC `6334`,但读写协议等价。

## 前置

```bash
pip install requests   # 实际上脚本只用标准库 urllib,requests 不需要;装不装都行
```

只需 Python 3.8+,无第三方依赖。

## 配置

直接改脚本里的常量,或通过环境变量覆盖:

```bash
export AI_EMBEDDING_API_KEY="sk-你的DashScope密钥"
export AI_EMBEDDING_API_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1"  # 默认
export AI_EMBEDDING_MODEL="text-embedding-v3"                                          # 默认
export QDRANT_HOST="localhost"                                                        # 默认
export QDRANT_HTTP_PORT="6333"                                                        # 默认
export QDRANT_COLLECTION="fitness_knowledge"                                          # 默认
export AI_RAG_TOP_K="5"
export AI_RAG_MIN_SCORE="0.6"
```

## 用法

```bash
cd fitness-admin/fitness-ai/scripts

# 1. 灌库:把内置 7 条示例知识写入 Qdrant
python rag_debug.py ingest

# 2. 检索:对 5 个示例 query 跑 RAG,打印 Top-K
python rag_debug.py search

# 3. 一键灌库 + 检索(推荐调试入口)
python rag_debug.py all

# 4. 清空 collection 重建(慎用)
python rag_debug.py reset
```

### 示例输出(in all 模式末尾)

```
Q: 新手减脂应该怎么安排训练?
  #1  score=0.7823  id=5f1a...
      title    : 新手减脂训练指南
      category : training
      sourceId : 1
      chunk    : 对于新手减脂,推荐全身训练3天/周。全身训练消耗热量高,适合减脂目标。...
```

## 调试流程建议

1. **先看服务在不在**
   ```bash
   curl http://localhost:6333/collections/fitness_knowledge
   ```
2. **跑 `python rag_debug.py all`**,按以下顺序看错误:
   - `[ERROR] AI_EMBEDDING_API_KEY 未设置` → 配环境变量
   - `Embedding 维度异常: 期望 1024, 实测 1536` → `dimensions` 字段被忽略,模型自动输出 1536(确认 `AI_EMBEDDING_MODEL` 是 `text-embedding-v3`,旧模型 `text-embedding-v2` 默认 1536 维)
   - `upsert 失败: HTTP 400, resp={... vectors size 1024 ...}` → 检查 collection 创建时的 `size` 是否一致
   - `搜索无命中` → 把 `AI_RAG_MIN_SCORE` 调到 0.3 或 0.4 再试,排查阈值问题
3. **Java 端联调**:灌库成功、检索命中后,确认 `KnowledgeIndexJob` 能正常消费 `vector_status='pending'` 的记录,流程闭环。

## 扩展示例知识

直接在 `SAMPLE_KNOWLEDGE` 列表里追加 `{"sourceId", "category", "title", "content"}` 字典即可。生产数据建议直接从 `knowledge_base` 表读取后批量灌库。