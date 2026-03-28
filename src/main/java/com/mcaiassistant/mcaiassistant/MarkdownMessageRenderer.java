package com.mcaiassistant.mcaiassistant;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 消息渲染器
 * 负责将 markdown 文本转换为带交互能力的 Adventure 组件。
 */
public final class MarkdownMessageRenderer {

    private static final Pattern MARKDOWN_CODE_BLOCK_PATTERN = Pattern.compile("```(?:\\w+)?\\s*\\R([\\s\\S]*?)```", Pattern.MULTILINE);
    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile("\\[([^\\]]+)]\\((https?://[^\\s)]+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");

    private MarkdownMessageRenderer() {
    }

    public static Component buildInteractiveMarkdownMessage(String aiPrefix, String content) {
        TextComponent.Builder builder = Component.text();
        builder.append(Component.text(aiPrefix, NamedTextColor.AQUA));

        if (content == null || content.isBlank()) {
            return builder.build();
        }

        int cursor = 0;
        Matcher codeMatcher = MARKDOWN_CODE_BLOCK_PATTERN.matcher(content);
        while (codeMatcher.find()) {
            if (codeMatcher.start() > cursor) {
                builder.append(renderMarkdownText(content.substring(cursor, codeMatcher.start())));
            }
            builder.append(buildCodeBlockComponent(codeMatcher.group(1)));
            cursor = codeMatcher.end();
        }

        if (cursor < content.length()) {
            builder.append(renderMarkdownText(content.substring(cursor)));
        }

        return builder.build();
    }

    public static Component buildInteractiveSearchMessage(String aiPrefix, SearchApiClient.SearchResult searchResult) {
        TextComponent.Builder builder = Component.text();

        if (searchResult.getSearchQuery() != null && !searchResult.getSearchQuery().trim().isEmpty()) {
            builder.append(Component.text("🔍 搜索: ", NamedTextColor.GRAY))
                .append(Component.text(searchResult.getSearchQuery(), NamedTextColor.GRAY))
                .append(Component.newline());
        }

        builder.append(buildInteractiveMarkdownMessage(aiPrefix, searchResult.getResultText()));

        if (searchResult.getLinks() != null && !searchResult.getLinks().isEmpty()) {
            builder.append(Component.newline())
                .append(Component.text("📎 相关链接: ", NamedTextColor.AQUA));

            for (int i = 0; i < searchResult.getLinks().size(); i++) {
                SearchApiClient.LinkInfo link = searchResult.getLinks().get(i);
                if (i > 0) {
                    builder.append(Component.text(" | ", NamedTextColor.GRAY));
                }
                builder.append(buildOpenUrlComponent(link.getTitle(), link.getUrl()));
            }
        }

        return builder.build();
    }

    private static Component renderMarkdownText(String text) {
        TextComponent.Builder builder = Component.text();
        if (text == null || text.isEmpty()) {
            return builder.build();
        }

        String normalized = text.replace("\r\n", "\n");
        String[] lines = normalized.split("\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            builder.append(renderMarkdownLine(lines[i]));
            if (i < lines.length - 1) {
                builder.append(Component.newline());
            }
        }

        return builder.build();
    }

    private static Component renderMarkdownLine(String line) {
        if (line == null || line.isEmpty()) {
            return Component.empty();
        }

        Matcher headingMatcher = HEADING_PATTERN.matcher(line);
        if (headingMatcher.matches()) {
            return parseInlineMarkdown(headingMatcher.group(2), NamedTextColor.WHITE, true);
        }

        return parseInlineMarkdown(line, NamedTextColor.WHITE, false);
    }

    private static Component parseInlineMarkdown(String text, NamedTextColor color, boolean forceBold) {
        TextComponent.Builder builder = Component.text();
        if (text == null || text.isEmpty()) {
            return builder.build();
        }

        int cursor = 0;
        while (cursor < text.length()) {
            int linkStart = text.indexOf('[', cursor);
            int emphasisStart = findNextEmphasisStart(text, cursor);
            int next = minPositive(linkStart, emphasisStart);

            if (next < 0) {
                builder.append(applyDecorations(Component.text(text.substring(cursor), color), forceBold, false, false));
                break;
            }

            if (next > cursor) {
                builder.append(applyDecorations(Component.text(text.substring(cursor, next), color), forceBold, false, false));
            }

            if (next == linkStart) {
                LinkParseResult link = tryParseLink(text, linkStart, forceBold);
                if (link != null) {
                    builder.append(link.component());
                    cursor = link.nextIndex();
                    continue;
                }
            }

            EmphasisParseResult emphasis = tryParseEmphasis(text, next, color, forceBold);
            if (emphasis != null) {
                builder.append(emphasis.component());
                cursor = emphasis.nextIndex();
                continue;
            }

            builder.append(applyDecorations(Component.text(String.valueOf(text.charAt(next)), color), forceBold, false, false));
            cursor = next + 1;
        }

        return builder.build();
    }

    private static int findNextEmphasisStart(String text, int fromIndex) {
        int star = text.indexOf('*', fromIndex);
        int underscore = text.indexOf('_', fromIndex);
        return minPositive(star, underscore);
    }

    private static int minPositive(int a, int b) {
        if (a < 0) {
            return b;
        }
        if (b < 0) {
            return a;
        }
        return Math.min(a, b);
    }

    private static LinkParseResult tryParseLink(String text, int start, boolean forceBold) {
        Matcher matcher = MARKDOWN_LINK_PATTERN.matcher(text);
        matcher.region(start, text.length());
        if (!matcher.lookingAt()) {
            return null;
        }

        Component linkComponent = buildOpenUrlComponent(matcher.group(1), matcher.group(2));
        if (forceBold) {
            linkComponent = linkComponent.decorate(TextDecoration.BOLD);
        }
        return new LinkParseResult(linkComponent, matcher.end());
    }

    private static EmphasisParseResult tryParseEmphasis(String text, int start, NamedTextColor color, boolean forceBold) {
        if (start >= text.length()) {
            return null;
        }

        char marker = text.charAt(start);
        if (marker != '*' && marker != '_') {
            return null;
        }

        if (start + 1 < text.length() && text.charAt(start + 1) == marker) {
            int end = text.indexOf(String.valueOf(marker).repeat(2), start + 2);
            if (end > start + 2) {
                String inner = text.substring(start + 2, end);
                Component component = parseInlineMarkdown(inner, color, forceBold || true, false);
                return new EmphasisParseResult(component, end + 2);
            }
        }

        int end = text.indexOf(marker, start + 1);
        if (end > start + 1) {
            String inner = text.substring(start + 1, end);
            Component component = parseInlineMarkdown(inner, color, forceBold, true);
            return new EmphasisParseResult(component, end + 1);
        }

        return null;
    }

    private static Component parseInlineMarkdown(String text, NamedTextColor color, boolean bold, boolean italic) {
        TextComponent.Builder builder = Component.text();
        if (text == null || text.isEmpty()) {
            return builder.build();
        }

        int cursor = 0;
        while (cursor < text.length()) {
            int linkStart = text.indexOf('[', cursor);
            int emphasisStart = findNextEmphasisStart(text, cursor);
            int next = minPositive(linkStart, emphasisStart);

            if (next < 0) {
                builder.append(applyDecorations(Component.text(text.substring(cursor), color), bold, italic, false));
                break;
            }

            if (next > cursor) {
                builder.append(applyDecorations(Component.text(text.substring(cursor, next), color), bold, italic, false));
            }

            if (next == linkStart) {
                LinkParseResult link = tryParseLink(text, linkStart, bold);
                if (link != null) {
                    Component linkComponent = link.component();
                    if (italic) {
                        linkComponent = linkComponent.decorate(TextDecoration.ITALIC);
                    }
                    builder.append(linkComponent);
                    cursor = link.nextIndex();
                    continue;
                }
            }

            EmphasisParseResult emphasis = tryParseNestedEmphasis(text, next, color, bold, italic);
            if (emphasis != null) {
                builder.append(emphasis.component());
                cursor = emphasis.nextIndex();
                continue;
            }

            builder.append(applyDecorations(Component.text(String.valueOf(text.charAt(next)), color), bold, italic, false));
            cursor = next + 1;
        }

        return builder.build();
    }

    private static EmphasisParseResult tryParseNestedEmphasis(String text, int start, NamedTextColor color, boolean bold, boolean italic) {
        if (start >= text.length()) {
            return null;
        }

        char marker = text.charAt(start);
        if (marker != '*' && marker != '_') {
            return null;
        }

        if (start + 1 < text.length() && text.charAt(start + 1) == marker) {
            int end = text.indexOf(String.valueOf(marker).repeat(2), start + 2);
            if (end > start + 2) {
                String inner = text.substring(start + 2, end);
                Component component = parseInlineMarkdown(inner, color, true, italic);
                return new EmphasisParseResult(component, end + 2);
            }
        }

        int end = text.indexOf(marker, start + 1);
        if (end > start + 1) {
            String inner = text.substring(start + 1, end);
            Component component = parseInlineMarkdown(inner, color, bold, true);
            return new EmphasisParseResult(component, end + 1);
        }

        return null;
    }

    private static Component applyDecorations(Component component, boolean bold, boolean italic, boolean underlined) {
        if (bold) {
            component = component.decorate(TextDecoration.BOLD);
        }
        if (italic) {
            component = component.decorate(TextDecoration.ITALIC);
        }
        if (underlined) {
            component = component.decorate(TextDecoration.UNDERLINED);
        }
        return component;
    }

    private static Component buildCodeBlockComponent(String codeContent) {
        String normalizedCode = normalizeCodeBlockContent(codeContent);
        TextComponent.Builder builder = Component.text();
        builder.append(Component.newline())
            .append(Component.text("[代码块点击填入命令框]", NamedTextColor.GOLD, TextDecoration.BOLD)
                .clickEvent(ClickEvent.suggestCommand(normalizedCode))
                .hoverEvent(HoverEvent.showText(Component.text()
                    .append(Component.text("点击后将代码填入命令输入框", NamedTextColor.YELLOW))
                    .append(Component.text("\n内容:\n", NamedTextColor.GRAY))
                    .append(Component.text(normalizedCode, NamedTextColor.WHITE))
                    .build())))
            .append(Component.newline())
            .append(Component.text(normalizedCode, NamedTextColor.GRAY))
            .append(Component.newline());
        return builder.build();
    }

    private static String normalizeCodeBlockContent(String codeContent) {
        if (codeContent == null) {
            return "";
        }
        String normalized = codeContent.replace("\r\n", "\n").trim();
        List<String> lines = normalized.lines().map(String::stripTrailing).toList();
        return String.join("\n", lines).trim();
    }

    private static Component buildOpenUrlComponent(String title, String url) {
        String safeTitle = (title == null || title.isBlank()) ? url : title;
        return Component.text(safeTitle, NamedTextColor.BLUE)
            .decorate(TextDecoration.UNDERLINED)
            .clickEvent(ClickEvent.openUrl(url))
            .hoverEvent(HoverEvent.showText(Component.text("点击打开: " + url, NamedTextColor.YELLOW)));
    }

    private record LinkParseResult(Component component, int nextIndex) {
    }

    private record EmphasisParseResult(Component component, int nextIndex) {
    }
}
