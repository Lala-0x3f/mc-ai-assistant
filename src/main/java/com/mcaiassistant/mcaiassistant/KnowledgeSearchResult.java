package com.mcaiassistant.mcaiassistant;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库检索结果
 */
public class KnowledgeSearchResult {

    private final String aiAnswer;
    private final List<KnowledgeSnippet> snippets;

    public KnowledgeSearchResult(String aiAnswer, List<KnowledgeSnippet> snippets) {
        this.aiAnswer = aiAnswer == null ? null : aiAnswer.trim();
        this.snippets = snippets == null ? Collections.emptyList() : List.copyOf(snippets);
    }

    public static KnowledgeSearchResult empty() {
        return new KnowledgeSearchResult(null, Collections.emptyList());
    }

    public boolean isEmpty() {
        return (aiAnswer == null || aiAnswer.isEmpty()) && snippets.isEmpty();
    }

    public String getAiAnswer() {
        return aiAnswer;
    }

    public List<KnowledgeSnippet> getSnippets() {
        return snippets;
    }

    /**
     * 将检索结果包装为 <knowledge_search> 片段，供系统提示使用。
     */
    public String toPromptPayload() {
        if (isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("<knowledge_search>\n");

        if (aiAnswer != null && !aiAnswer.isEmpty()) {
            builder.append("<ai_search>\n")
                    .append(aiAnswer.trim())
                    .append("\n</ai_search>\n");
        }

        if (!snippets.isEmpty()) {
            builder.append("<direct_hits>\n");
            for (KnowledgeSnippet snippet : snippets) {
                builder.append("<document path=\"")
                        .append(snippet.getRelativePath())
                        .append("\">\n")
                        .append(snippet.getPreview())
                        .append("\n</document>\n");
            }
            builder.append("</direct_hits>\n");
        }

        builder.append("</knowledge_search>");
        return builder.toString();
    }

    /**
     * 将检索结果转换为工具返回内容（纯文本）
     */
    public String toToolContent() {
        if (isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("知识库检索结果:\n");
        if (aiAnswer != null && !aiAnswer.isEmpty()) {
            builder.append("- AI 搜索摘要:\n")
                    .append(aiAnswer.trim())
                    .append("\n");
        }
        if (!snippets.isEmpty()) {
            builder.append("- 直接命中片段:\n");
            int index = 1;
            for (KnowledgeSnippet snippet : snippets) {
                builder.append("  ")
                        .append(index++)
                        .append(". [")
                        .append(snippet.getRelativePath())
                        .append("]\n")
                        .append(snippet.getPreview())
                        .append("\n");
            }
        }
        return builder.toString().trim();
    }

    @Override
    public String toString() {
        return "KnowledgeSearchResult{" +
                "aiAnswer='" + (aiAnswer == null ? "" : aiAnswer.substring(0, Math.min(40, aiAnswer.length()))) + '\'' +
                ", snippets=" + snippets.stream().map(KnowledgeSnippet::getRelativePath).collect(Collectors.toList()) +
                '}';
    }
}
