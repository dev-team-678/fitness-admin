#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RAG 数据灌库与检索调试脚本

用途:
  1. 直接调用 Embedding 接口(阿里云 DashScope text-embedding-v3,1024 维),
     把示例健身知识切块、向量化、写入 Qdrant collection=fitness_knowledge。
  2. 模拟一次 RAG 检索流程,验证 Embedding → Qdrant.search → 返回 Top-K 链路。

与 Java 端对齐点:
  - Collection 维度: 1024 (text-embedding-v3)
  - 距离度量: Cosine
  - Qdrant gRPC 端口: 6334 (HTTP REST 用 6333,本脚本走 HTTP REST,无需 grpc 依赖)
  - Payload 字段: sourceType, sourceId, chunkText, title, category
  - scoreThreshold 默认 0.6

依赖:
  pip install requests

用法:
  python rag_debug.py ingest     # 把内置示例知识写入 Qdrant
  python rag_debug.py search     # 用示例 query 跑检索
  python rag_debug.py all        # 灌库 + 检索(推荐调试入口)
  python rag_debug.py reset      # 删 collection 重建(慎用,会清空数据)
"""
from __future__ import annotations

import os
import sys
import json
import time
import uuid
import re
import urllib.request
import urllib.error
from typing import List, Dict, Any, Tuple

# ----------------------------- 配置 -----------------------------

# Qdrant HTTP REST 地址(Java 客户端走 6334 gRPC,HTTP REST 在 6333)
QDRANT_HOST = os.environ.get("QDRANT_HOST", "localhost")
QDRANT_HTTP_PORT = int(os.environ.get("QDRANT_HTTP_PORT", "6333"))
COLLECTION = os.environ.get("QDRANT_COLLECTION", "fitness_knowledge")
VECTOR_DIM = int(os.environ.get("AI_EMBEDDING_DIMENSION", "1024"))

# Embedding (阿里云 DashScope, OpenAI 兼容协议)
EMBED_BASE = os.environ.get(
    "AI_EMBEDDING_API_BASE_URL",
    "https://dashscope.aliyuncs.com/compatible-mode/v1",
)
EMBED_KEY = os.environ.get(
    "AI_EMBEDDING_API_KEY",
    "",
)
EMBED_MODEL = os.environ.get("AI_EMBEDDING_MODEL", "text-embedding-v3")

# 检索参数
TOP_K = int(os.environ.get("AI_RAG_TOP_K", "5"))
MIN_SCORE = float(os.environ.get("AI_RAG_MIN_SCORE", "0.6"))

# 切块参数(与 KnowledgeChunker 对齐)
CHUNK_SIZE = int(os.environ.get("AI_CHUNK_SIZE", "500"))
CHUNK_OVERLAP = int(os.environ.get("AI_CHUNK_OVERLAP", "50"))


# ----------------------------- 切块器(对齐 KnowledgeChunker) -----------------------------

PARAGRAPH_RE = re.compile(r"\r?\n\r?\n")
SENTENCE_RE = re.compile(r"(?<=[。!?;])|(?<=[.!?;])\s+|(?<=[，,])\s*")


def split_into_chunks(content: str, chunk_size: int = CHUNK_SIZE, overlap: int = CHUNK_OVERLAP) -> List[str]:
    """与 Java KnowledgeChunker.split 行为一致的 Python 版切块器。"""
    if not content:
        return []
    text = content.strip()
    if not text:
        return []

    chunks: List[str] = []
    for paragraph in PARAGRAPH_RE.split(text):
        p = paragraph.strip()
        if not p:
            continue
        if len(p) <= chunk_size:
            chunks.append(p)
        else:
            chunks.extend(_split_long(p, chunk_size, overlap))
    return _merge_tiny(chunks, chunk_size)


def _split_long(paragraph: str, chunk_size: int, overlap: int) -> List[str]:
    sentences: List[str] = []
    last = 0
    for m in SENTENCE_RE.finditer(paragraph):
        end = m.end()
        if end > last:
            sentences.append(paragraph[last:end].strip())
        last = end
    if last < len(paragraph):
        sentences.append(paragraph[last:].strip())

    result: List[str] = []
    buf = ""
    for s in sentences:
        if not s:
            continue
        if len(s) >= chunk_size:
            if buf:
                result.append(buf.strip())
                buf = ""
            for i in range(0, len(s), chunk_size - overlap):
                end = min(i + chunk_size, len(s))
                result.append(s[i:end])
                if end >= len(s):
                    break
            continue
        if len(buf) + len(s) > chunk_size:
            result.append(buf.strip())
            tail = buf
            overlap_start = max(0, len(tail) - overlap)
            buf = ""
            if overlap > 0 and overlap_start < len(tail):
                buf = tail[overlap_start:]
        if buf:
            buf += " "
        buf += s
    if buf:
        result.append(buf.strip())
    return result


def _merge_tiny(chunks: List[str], target: int) -> List[str]:
    if len(chunks) < 2:
        return chunks
    merged: List[str] = []
    buf = ""
    for c in chunks:
        if not c:
            continue
        if not buf:
            buf = c
        elif len(buf) + len(c) <= target:
            buf += "\n" + c
        else:
            merged.append(buf.strip())
            buf = c
    if buf:
        merged.append(buf.strip())
    return merged


# ----------------------------- Embedding -----------------------------

def embed_texts(texts: List[str]) -> List[List[float]]:
    """调 DashScope text-embedding-v3,返回 dim=1024 向量。"""
    if not texts:
        return []
    if not EMBED_KEY:
        raise RuntimeError("AI_EMBEDDING_API_KEY 未设置,无法调用 Embedding")

    url = EMBED_BASE.rstrip("/") + "/embeddings"
    body = {
        "model": EMBED_MODEL,
        "dimensions": VECTOR_DIM,
        "input": texts,
    }
    headers = {
        "Authorization": "Bearer " + EMBED_KEY,
        "Content-Type": "application/json",
    }
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        payload = json.loads(resp.read().decode("utf-8"))

    data = payload.get("data") or []
    # 按 index 排序保证顺序对齐
    data.sort(key=lambda x: x.get("index", 0))
    vectors = []
    for item in data:
        emb = item.get("embedding") or []
        if len(emb) != VECTOR_DIM:
            raise RuntimeError(
                f"Embedding 维度异常: 期望 {VECTOR_DIM}, 实测 {len(emb)}, model={EMBED_MODEL}"
            )
        vectors.append([float(x) for x in emb])
    return vectors


# ----------------------------- Qdrant HTTP REST -----------------------------

def qdrant_url(path: str) -> str:
    return f"http://{QDRANT_HOST}:{QDRANT_HTTP_PORT}{path}"


def qdrant_request(path: str, method: str = "GET", body: Any = None, expect_json: bool = True):
    url = qdrant_url(path)
    data = None
    headers = {"Content-Type": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode("utf-8")
            if expect_json and raw:
                return resp.status, json.loads(raw)
            return resp.status, raw
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="ignore")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw


def collection_exists() -> bool:
    code, body = qdrant_request(f"/collections/{COLLECTION}")
    if code == 200:
        return True
    if code == 404:
        return False
    raise RuntimeError(f"查询 collection 失败: HTTP {code}, body={body}")


def ensure_collection(recreate: bool = False) -> None:
    """创建或重建 collection(1024 维 + Cosine)。"""
    if recreate:
        qdrant_request(f"/collections/{COLLECTION}", method="DELETE")
        time.sleep(0.5)

    if not collection_exists():
        body = {
            "vectors": {
                "size": VECTOR_DIM,
                "distance": "Cosine",
            }
        }
        code, resp = qdrant_request(f"/collections/{COLLECTION}", method="PUT", body=body)
        if code not in (200, 201):
            raise RuntimeError(f"创建 collection 失败: HTTP {code}, resp={resp}")
        print(f"[OK] collection 创建成功: {COLLECTION} (dim={VECTOR_DIM}, distance=Cosine)")
    else:
        print(f"[OK] collection 已存在: {COLLECTION}")


def upsert_points(points: List[Dict[str, Any]]) -> None:
    """批量 upsert,Qdrant 单次建议 ≤ 256 点。"""
    if not points:
        return
    body = {"points": points}
    code, resp = qdrant_request(
        f"/collections/{COLLECTION}/points",
        method="PUT",
        body=body,
    )
    if code not in (200, 201):
        raise RuntimeError(f"upsert 失败: HTTP {code}, resp={resp}")


def search_points(query_vec: List[float], top_k: int = TOP_K, score_threshold: float = MIN_SCORE) -> List[Dict[str, Any]]:
    body = {
        "vector": query_vec,
        "limit": top_k,
        "with_payload": True,
        "score_threshold": score_threshold,
    }
    code, resp = qdrant_request(f"/collections/{COLLECTION}/points/search", method="POST", body=body)
    if code != 200:
        raise RuntimeError(f"search 失败: HTTP {code}, resp={resp}")
    return resp.get("result") or []


def collection_info() -> Dict[str, Any]:
    code, resp = qdrant_request(f"/collections/{COLLECTION}")
    if code != 200:
        raise RuntimeError(f"查询 collection info 失败: HTTP {code}, resp={resp}")
    return resp.get("result") or {}


# ----------------------------- 示例数据(与 sql/01_test_data.sql 一致) -----------------------------

SAMPLE_KNOWLEDGE: List[Dict[str, Any]] = [
    {
        "sourceId": 1,
        "category": "training",
        "title": "新手减脂训练指南",
        "content": (
            "对于新手减脂,推荐全身训练3天/周。全身训练消耗热量高,适合减脂目标。\n\n"
            "训练安排:\n"
            "- 每周3次,隔天训练\n"
            "- 每次45-60分钟\n"
            "- 选择复合动作为主(深蹲、俯卧撑、划船)\n"
            "- 组间休息45-60秒\n\n"
            "注意事项:\n"
            "- 减脂关键是热量缺口,训练是辅助\n"
            "- 不要只做有氧,力量训练同样重要\n"
            "- 循序渐进,不要一开始就高强度"
        ),
    },
    {
        "sourceId": 2,
        "category": "training",
        "title": "渐进超负荷原则",
        "content": (
            "渐进超负荷是肌肉增长的核心原则。肌肉只在受到超出当前适应水平的刺激时才会增长。\n\n"
            "实施方法:\n"
            "1. 增加重量:每次增加2.5-5%\n"
            "2. 增加次数:每组多做1-2次\n"
            "3. 增加组数:增加1组\n"
            "4. 缩短休息时间:减少15秒\n"
            "5. 提高动作质量:更好的控制和幅度\n\n"
            "建议:优先增加次数到目标上限,再增加重量。"
        ),
    },
    {
        "sourceId": 3,
        "category": "nutrition",
        "title": "增肌期蛋白质摄入",
        "content": (
            "增肌期蛋白质摄入建议:\n\n"
            "每公斤体重1.6-2.2g蛋白质/天。\n\n"
            "蛋白质来源:\n"
            "- 鸡胸肉: 31g/100g\n"
            "- 牛肉: 26g/100g\n"
            "- 鸡蛋: 6g/个\n"
            "- 牛奶: 3.2g/100ml\n"
            "- 豆腐: 8g/100g\n"
            "- 乳清蛋白粉: 24-27g/勺\n\n"
            "摄入时机:\n"
            "- 分3-4餐均匀摄入\n"
            "- 训练后30分钟内摄入20-40g\n"
            "- 睡前可补充酪蛋白"
        ),
    },
    {
        "sourceId": 4,
        "category": "nutrition",
        "title": "减脂期饮食建议",
        "content": (
            "减脂的关键是热量缺口,建议每天减少300-500kcal。\n\n"
            "饮食原则:\n"
            "- 蛋白质: 每公斤体重1.6-2.2g(保护肌肉)\n"
            "- 脂肪: 每公斤体重0.8-1g(维持激素)\n"
            "- 碳水: 剩余热量分配(优先训练日多摄入)\n\n"
            "避免:\n"
            "- 极端节食(不低于1200kcal/天)\n"
            "- 完全不吃碳水\n"
            "- 只看体重不看体脂"
        ),
    },
    {
        "sourceId": 5,
        "category": "recovery",
        "title": "训练后肌肉酸痛(DOMS)",
        "content": (
            "延迟性肌肉酸痛(DOMS)通常在训练后24-72小时达到高峰,是正常现象。\n\n"
            "缓解方法:\n"
            "1. 轻度活动(散步、轻量有氧)促进血液循环\n"
            "2. 充分拉伸和泡沫轴放松\n"
            "3. 保证充足睡眠(7-9小时)\n"
            "4. 补充蛋白质和抗氧化食物\n"
            "5. 热水浴或冷热交替浴\n\n"
            "注意:\n"
            "- DOMS不等于训练效果好\n"
            "- 关节疼痛不是DOMS,需要重视"
        ),
    },
    {
        "sourceId": 6,
        "category": "injury",
        "title": "膝盖损伤训练注意事项",
        "content": (
            "膝盖有伤的训练注意事项:\n\n"
            "避免的动作:\n"
            "- 深蹲(尤其是全蹲)\n"
            "- 箭步蹲\n"
            "- 跳跃类动作\n"
            "- 长距离跑步\n\n"
            "推荐的动作:\n"
            "- 高脚杯深蹲(幅度可控)\n"
            "- 臀桥(不负重)\n"
            "- 上肢训练(不影响膝盖)\n"
            "- 游泳、椭圆机(低冲击有氧)\n\n"
            "核心原则:无痛范围内训练,加强股四头肌和臀部肌群力量"
        ),
    },
    {
        "sourceId": 7,
        "category": "training",
        "title": "推拉腿分化训练方案",
        "content": (
            "推拉腿(Push/Pull/Legs)是经典的3天分化方案,适合中级以上训练者。\n\n"
            "推日(Push): 胸、肩、肱三头肌\n"
            "- 平板卧推、上斜推举、推肩、绳索下压\n\n"
            "拉日(Pull): 背、肱二头肌\n"
            "- 引体向上、划船、硬拉、弯举\n\n"
            "腿日(Legs): 股四头肌、腘绳肌、臀部\n"
            "- 深蹲、罗马尼亚硬拉、腿举\n\n"
            "优势:\n"
            "- 每个肌群每周可练2次\n"
            "- 充分的恢复时间\n"
            "- 训练量分配合理"
        ),
    },
]


SAMPLE_QUERIES: List[str] = [
    "新手减脂应该怎么安排训练?",
    "增肌每天要吃多少蛋白质?",
    "训练后肌肉酸痛怎么办?",
    "膝盖有伤能做什么动作?",
    "推拉腿怎么分配?",
]


# ----------------------------- 业务动作 -----------------------------

def truncate(s: str, n: int) -> str:
    return s if len(s) <= n else s[:n]


def cmd_ingest() -> None:
    """灌库:切块 → embedding → upsert 到 Qdrant。"""
    ensure_collection(recreate=False)

    print(f"[INFO] 开始处理 {len(SAMPLE_KNOWLEDGE)} 条知识 ...")
    total_chunks = 0
    total_uploaded = 0

    for kb in SAMPLE_KNOWLEDGE:
        chunks = split_into_chunks(kb["content"], CHUNK_SIZE, CHUNK_OVERLAP)
        if not chunks:
            print(f"[WARN] 切块为空,跳过: sourceId={kb['sourceId']}")
            continue
        print(f"  - sourceId={kb['sourceId']} title='{kb['title']}' chunks={len(chunks)}")

        vectors = embed_texts(chunks)
        if len(vectors) != len(chunks):
            raise RuntimeError(
                f"Embedding 数量不匹配: chunks={len(chunks)}, vectors={len(vectors)}"
            )

        # 构造 Qdrant points,与 Java KnowledgeIndexJob 的 payload 字段保持一致
        points: List[Dict[str, Any]] = []
        for i, (chunk, vec) in enumerate(zip(chunks, vectors)):
            vector_id = str(uuid.uuid4())
            payload = {
                "sourceType": "knowledge",
                "sourceId": kb["sourceId"],
                "chunkText": truncate(chunk, 2000),
                "title": kb["title"],
                "category": kb["category"],
                "chunkIndex": i,
            }
            points.append({
                "id": vector_id,
                "vector": vec,
                "payload": payload,
            })

        # Qdrant 单批 ≤ 256 点,这里不会超
        upsert_points(points)
        total_chunks += len(chunks)
        total_uploaded += len(points)

    info = collection_info()
    print("\n[OK] 灌库完成")
    print(f"  本次写入 chunks: {total_chunks} (points: {total_uploaded})")
    print(f"  collection.points_count: {info.get('points_count')}")
    print(f"  collection.indexed_vectors_count: {info.get('indexed_vectors_count')}")


def cmd_search() -> None:
    """检索:对一组示例 query 跑 RAG,打印 Top-K。"""
    if not collection_exists():
        print(f"[ERROR] collection {COLLECTION} 不存在,请先执行 ingest")
        sys.exit(1)

    info = collection_info()
    print(f"[INFO] collection: points_count={info.get('points_count')}, "
          f"vectors_count={info.get('indexed_vectors_count')}, "
          f"dim={info.get('config', {}).get('params', {}).get('vectors', {}).get('size')}")
    print(f"[INFO] 检索参数: topK={TOP_K}, minScore={MIN_SCORE}, model={EMBED_MODEL}\n")

    for q in SAMPLE_QUERIES:
        print(f"Q: {q}")
        qvec = embed_texts([q])[0]
        hits = search_points(qvec, TOP_K, MIN_SCORE)

        if not hits:
            print("  (无命中,可能 score 全部低于阈值,或 collection 为空)")
            print()
            continue

        for i, h in enumerate(hits, 1):
            payload = h.get("payload") or {}
            score = h.get("score", 0.0)
            print(f"  #{i}  score={score:.4f}  id={h.get('id')}")
            print(f"      title    : {payload.get('title')}")
            print(f"      category : {payload.get('category')}")
            print(f"      sourceId : {payload.get('sourceId')}")
            text = (payload.get("chunkText") or "").replace("\n", " ").strip()
            print(f"      chunk    : {truncate(text, 120)}")
        print()


def cmd_reset() -> None:
    """删 collection 重建。"""
    print(f"[WARN] 即将删除并重建 collection: {COLLECTION}")
    ensure_collection(recreate=True)


def cmd_all() -> None:
    cmd_ingest()
    print("\n" + "=" * 60 + "\n")
    cmd_search()


# ----------------------------- 入口 -----------------------------

USAGE = """用法:
  python rag_debug.py ingest   灌库
  python rag_debug.py search   检索测试
  python rag_debug.py all      灌库 + 检索(推荐)
  python rag_debug.py reset    删除并重建 collection

环境变量(可覆盖默认值):
  QDRANT_HOST                 默认 localhost
  QDRANT_HTTP_PORT            默认 6333
  QDRANT_COLLECTION           默认 fitness_knowledge
  AI_EMBEDDING_API_BASE_URL   默认 https://dashscope.aliyuncs.com/compatible-mode/v1
  AI_EMBEDDING_API_KEY        默认空(必填)
  AI_EMBEDDING_MODEL          默认 text-embedding-v3
  AI_EMBEDDING_DIMENSION      默认 1024
  AI_RAG_TOP_K                默认 5
  AI_RAG_MIN_SCORE            默认 0.6
  AI_CHUNK_SIZE               默认 500
  AI_CHUNK_OVERLAP            默认 50
"""


def main(argv: List[str]) -> int:
    if len(argv) < 2:
        print(USAGE)
        return 1

    cmd = argv[1].lower()
    try:
        if cmd == "ingest":
            cmd_ingest()
        elif cmd == "search":
            cmd_search()
        elif cmd == "all":
            cmd_all()
        elif cmd == "reset":
            cmd_reset()
        else:
            print(USAGE)
            return 1
        return 0
    except Exception as e:
        print(f"[ERROR] {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))