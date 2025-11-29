package com.mcaiassistant.mcaiassistant;

/**
 * 直接检索命中的文档片段
 */
public class KnowledgeSnippet {

    private final String relativePath;
    private final String preview;

    public KnowledgeSnippet(String relativePath, String preview) {
        this.relativePath = relativePath;
        this.preview = preview == null ? "" : preview.trim();
    }

    public String getRelativePath() {
        return relativePath;
    }

    public String getPreview() {
        return preview;
    }
}
