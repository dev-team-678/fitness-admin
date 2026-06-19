package com.fitness.admin.ai.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识内容切块工具
 * 先按段落切,过长的段落再按句号/问号/感叹号/中英文逗号细切,保留 overlap。
 */
public class KnowledgeChunker {

    private static final Pattern PARAGRAPH = Pattern.compile("\\r?\\n\\r?\\n");
    private static final Pattern SENTENCE = Pattern.compile("(?<=[。!?;])|(?<=[.!?;])\\s+|(?<=[，,])\\s*");

    public List<String> split(String content, int chunkSize, int chunkOverlap) {
        if (content == null) return List.of();
        String normalized = content.trim();
        if (normalized.isEmpty()) return List.of();

        List<String> chunks = new ArrayList<>();
        for (String paragraph : PARAGRAPH.split(normalized)) {
            String p = paragraph.strip();
            if (p.isEmpty()) continue;
            if (p.length() <= chunkSize) {
                chunks.add(p);
                continue;
            }
            chunks.addAll(splitLongParagraph(p, chunkSize, chunkOverlap));
        }
        return mergeTinyChunks(chunks, chunkSize);
    }

    private List<String> splitLongParagraph(String paragraph, int chunkSize, int chunkOverlap) {
        List<String> sentences = new ArrayList<>();
        Matcher m = SENTENCE.matcher(paragraph);
        int last = 0;
        while (m.find()) {
            int end = m.end();
            if (end > last) {
                sentences.add(paragraph.substring(last, end).strip());
            }
            last = end;
        }
        if (last < paragraph.length()) {
            sentences.add(paragraph.substring(last).strip());
        }

        List<String> result = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (String sentence : sentences) {
            if (sentence.isEmpty()) continue;
            if (sentence.length() >= chunkSize) {
                if (buffer.length() > 0) {
                    result.add(buffer.toString().strip());
                    buffer.setLength(0);
                }
                for (int i = 0; i < sentence.length(); i += chunkSize - chunkOverlap) {
                    int end = Math.min(i + chunkSize, sentence.length());
                    result.add(sentence.substring(i, end));
                    if (end >= sentence.length()) break;
                }
                continue;
            }
            if (buffer.length() + sentence.length() > chunkSize) {
                result.add(buffer.toString().strip());
                String tail = buffer.toString();
                int overlapStart = Math.max(0, tail.length() - chunkOverlap);
                buffer.setLength(0);
                if (chunkOverlap > 0 && overlapStart < tail.length()) {
                    buffer.append(tail, overlapStart, tail.length());
                }
            }
            if (buffer.length() > 0) buffer.append(' ');
            buffer.append(sentence);
        }
        if (buffer.length() > 0) {
            result.add(buffer.toString().strip());
        }
        return result;
    }

    private List<String> mergeTinyChunks(List<String> chunks, int targetSize) {
        if (chunks.size() < 2) return chunks;
        List<String> merged = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String c : chunks) {
            if (c.isEmpty()) continue;
            if (buf.length() == 0) {
                buf.append(c);
            } else if (buf.length() + c.length() <= targetSize) {
                buf.append('\n').append(c);
            } else {
                merged.add(buf.toString().strip());
                buf.setLength(0);
                buf.append(c);
            }
        }
        if (buf.length() > 0) {
            merged.add(buf.toString().strip());
        }
        return merged;
    }
}
