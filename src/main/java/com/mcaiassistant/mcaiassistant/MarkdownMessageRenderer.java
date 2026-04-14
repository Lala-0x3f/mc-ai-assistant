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

    private static final Pattern MARKDOWN_CODE_BLOCK_PATTERN = Pattern.compile("```([^\r\n`]*)\\R([\\s\\S]*?)```", Pattern.MULTILINE);
    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile("\\[([^\\]]+)]\\((https?://[^\\s)]+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern ORDERED_LIST_PATTERN = Pattern.compile("^(\\s*)(\\d+)\\.\\s+(.+)$");
    private static final Pattern UNORDERED_LIST_PATTERN = Pattern.compile("^(\\s*)([-*+])\\s+(.+)$");
    private static final Pattern BLOCKQUOTE_PATTERN = Pattern.compile("^(\\s*)>\\s?(.*)$");
    private static final Pattern HORIZONTAL_RULE_PATTERN = Pattern.compile("^\\s{0,3}((\\*\\s*){3,}|(-\\s*){3,}|(_\\s*){3,})$");
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`([^`]+)`");
    private static final int LIST_INDENT_UNIT = 2;

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
            builder.append(buildCodeBlockComponent(codeMatcher.group(1), codeMatcher.group(2)));
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
        boolean previousLineWasList = false;
        for (int i = 0; i < lines.length; i++) {
            LineRenderResult lineResult = renderMarkdownLine(lines[i]);
            builder.append(lineResult.component());
            if (i < lines.length - 1) {
                if (lineResult.listItem() || previousLineWasList || !lines[i].isEmpty()) {
                    builder.append(Component.newline());
                }
            }
            previousLineWasList = lineResult.listItem();
        }

        return builder.build();
    }

    private static LineRenderResult renderMarkdownLine(String line) {
        if (line == null || line.isEmpty()) {
            return new LineRenderResult(Component.empty(), false);
        }

        Matcher hrMatcher = HORIZONTAL_RULE_PATTERN.matcher(line);
        if (hrMatcher.matches()) {
            return new LineRenderResult(Component.text("────────────────", NamedTextColor.DARK_GRAY), false);
        }

        Matcher headingMatcher = HEADING_PATTERN.matcher(line);
        if (headingMatcher.matches()) {
            int level = headingMatcher.group(1).length();
            NamedTextColor headingColor = switch (level) {
                case 1 -> NamedTextColor.GOLD;
                case 2 -> NamedTextColor.YELLOW;
                case 3 -> NamedTextColor.AQUA;
                case 4 -> NamedTextColor.GREEN;
                default -> NamedTextColor.WHITE;
            };
            Component headingComponent = parseInlineMarkdown(headingMatcher.group(2), headingColor, true, false, false);
            return new LineRenderResult(headingComponent, false);
        }

        Matcher blockquoteMatcher = BLOCKQUOTE_PATTERN.matcher(line);
        if (blockquoteMatcher.matches()) {
            int indentLevel = calculateIndentLevel(blockquoteMatcher.group(1));
            String quoteBody = blockquoteMatcher.group(2);
            Component quotePrefix = Component.text(repeat("  ", indentLevel) + "▍ ", NamedTextColor.GRAY);
            Component quoteContent = parseInlineMarkdown(quoteBody, NamedTextColor.GRAY, false, true, false);
            return new LineRenderResult(Component.text().append(quotePrefix).append(quoteContent).build(), false);
        }

        Matcher orderedMatcher = ORDERED_LIST_PATTERN.matcher(line);
        if (orderedMatcher.matches()) {
            int indentLevel = calculateIndentLevel(orderedMatcher.group(1));
            String marker = orderedMatcher.group(2) + ". ";
            Component listComponent = buildListLine(marker, orderedMatcher.group(3), indentLevel);
            return new LineRenderResult(listComponent, true);
        }

        Matcher unorderedMatcher = UNORDERED_LIST_PATTERN.matcher(line);
        if (unorderedMatcher.matches()) {
            int indentLevel = calculateIndentLevel(unorderedMatcher.group(1));
            Component listComponent = buildListLine("• ", unorderedMatcher.group(3), indentLevel);
            return new LineRenderResult(listComponent, true);
        }

        return new LineRenderResult(parseInlineMarkdown(line, NamedTextColor.WHITE, false, false, false), false);
    }

    private static Component buildListLine(String marker, String text, int indentLevel) {
        TextComponent.Builder builder = Component.text();
        if (indentLevel > 0) {
            builder.append(Component.text(repeat("  ", indentLevel), NamedTextColor.GRAY));
        }
        builder.append(Component.text(marker, NamedTextColor.GRAY))
            .append(parseInlineMarkdown(text, NamedTextColor.WHITE, false, false, false));
        return builder.build();
    }

    private static int calculateIndentLevel(String indent) {
        if (indent == null || indent.isEmpty()) {
            return 0;
        }
        int spaces = 0;
        for (int i = 0; i < indent.length(); i++) {
            spaces += indent.charAt(i) == '\t' ? LIST_INDENT_UNIT : 1;
        }
        return spaces / LIST_INDENT_UNIT;
    }

    private static Component parseInlineMarkdown(String text, NamedTextColor color, boolean bold, boolean italic, boolean underlined) {
        TextComponent.Builder builder = Component.text();
        if (text == null || text.isEmpty()) {
            return builder.build();
        }

        int cursor = 0;
        while (cursor < text.length()) {
            int linkStart = text.indexOf('[', cursor);
            int codeStart = text.indexOf('`', cursor);
            int emphasisStart = findNextEmphasisStart(text, cursor);
            int next = minPositive(linkStart, minPositive(codeStart, emphasisStart));

            if (next < 0) {
                builder.append(applyDecorations(Component.text(text.substring(cursor), color), bold, italic, underlined));
                break;
            }

            if (next > cursor) {
                builder.append(applyDecorations(Component.text(text.substring(cursor, next), color), bold, italic, underlined));
            }

            if (next == linkStart) {
                LinkParseResult link = tryParseLink(text, linkStart, bold, italic, underlined);
                if (link != null) {
                    builder.append(link.component());
                    cursor = link.nextIndex();
                    continue;
                }
            }

            if (next == codeStart) {
                InlineCodeParseResult inlineCode = tryParseInlineCode(text, codeStart, bold, italic, underlined);
                if (inlineCode != null) {
                    builder.append(inlineCode.component());
                    cursor = inlineCode.nextIndex();
                    continue;
                }
            }

            EmphasisParseResult emphasis = tryParseEmphasis(text, next, color, bold, italic, underlined);
            if (emphasis != null) {
                builder.append(emphasis.component());
                cursor = emphasis.nextIndex();
                continue;
            }

            builder.append(applyDecorations(Component.text(String.valueOf(text.charAt(next)), color), bold, italic, underlined));
            cursor = next + 1;
        }

        return builder.build();
    }

    private static int findNextEmphasisStart(String text, int fromIndex) {
        int star = text.indexOf('*', fromIndex);
        int underscore = text.indexOf('_', fromIndex);
        int tilde = text.indexOf('~', fromIndex);
        return minPositive(minPositive(star, underscore), tilde);
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

    private static LinkParseResult tryParseLink(String text, int start, boolean bold, boolean italic, boolean underlined) {
        Matcher matcher = MARKDOWN_LINK_PATTERN.matcher(text);
        matcher.region(start, text.length());
        if (!matcher.lookingAt()) {
            return null;
        }

        Component linkComponent = buildOpenUrlComponent(matcher.group(1), matcher.group(2));
        linkComponent = applyDecorations(linkComponent, bold, italic, true || underlined);
        return new LinkParseResult(linkComponent, matcher.end());
    }

    private static InlineCodeParseResult tryParseInlineCode(String text, int start, boolean bold, boolean italic, boolean underlined) {
        Matcher matcher = INLINE_CODE_PATTERN.matcher(text);
        matcher.region(start, text.length());
        if (!matcher.lookingAt()) {
            return null;
        }

        String code = matcher.group(1);
        Component codeComponent = Component.text(code, NamedTextColor.GRAY)
            .decorate(TextDecoration.BOLD)
            .hoverEvent(HoverEvent.showText(Component.text("代码片段", NamedTextColor.YELLOW)));
        codeComponent = applyDecorations(codeComponent, bold, italic, underlined);
        return new InlineCodeParseResult(codeComponent, matcher.end());
    }

    private static EmphasisParseResult tryParseEmphasis(String text, int start, NamedTextColor color,
                                                        boolean bold, boolean italic, boolean underlined) {
        if (start >= text.length()) {
            return null;
        }

        if (text.startsWith("***", start) || text.startsWith("___", start)) {
            String marker = text.substring(start, start + 3);
            int end = text.indexOf(marker, start + 3);
            if (end > start + 3) {
                String inner = text.substring(start + 3, end);
                Component component = parseInlineMarkdown(inner, color, true, true, underlined);
                return new EmphasisParseResult(component, end + 3);
            }
        }

        if (text.startsWith("~~", start)) {
            int end = text.indexOf("~~", start + 2);
            if (end > start + 2) {
                String inner = text.substring(start + 2, end);
                Component component = parseInlineMarkdown(inner, color, bold, italic, true);
                return new EmphasisParseResult(component, end + 2);
            }
        }

        char marker = text.charAt(start);
        if (marker != '*' && marker != '_') {
            return null;
        }

        if (start + 1 < text.length() && text.charAt(start + 1) == marker) {
            String doubleMarker = String.valueOf(marker).repeat(2);
            int end = text.indexOf(doubleMarker, start + 2);
            if (end > start + 2) {
                String inner = text.substring(start + 2, end);
                Component component = parseInlineMarkdown(inner, color, true, italic, underlined);
                return new EmphasisParseResult(component, end + 2);
            }
        }

        int end = text.indexOf(marker, start + 1);
        if (end > start + 1) {
            String inner = text.substring(start + 1, end);
            Component component = parseInlineMarkdown(inner, color, bold, true, underlined);
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

    private static Component buildCodeBlockComponent(String language, String codeContent) {
        String normalizedCode = normalizeCodeBlockContent(codeContent);
        String normalizedLanguage = language == null ? "" : language.trim();
        TextComponent.Builder builder = Component.text();
        builder.append(Component.newline())
            .append(Component.text("[代码块点击填入命令框]", NamedTextColor.GOLD, TextDecoration.BOLD)
                .clickEvent(ClickEvent.suggestCommand(normalizedCode))
                .hoverEvent(HoverEvent.showText(Component.text()
                    .append(Component.text("点击后将代码填入命令输入框", NamedTextColor.YELLOW))
                    .append(normalizedLanguage.isEmpty()
                        ? Component.empty()
                        : Component.text("\n语言: " + normalizedLanguage, NamedTextColor.AQUA))
                    .append(Component.text("\n内容:\n", NamedTextColor.GRAY))
                    .append(Component.text(normalizedCode, NamedTextColor.WHITE))
                    .build())));

        if (!normalizedLanguage.isEmpty()) {
            builder.append(Component.newline())
                .append(Component.text("```" + normalizedLanguage, NamedTextColor.DARK_GRAY));
        }

        builder.append(Component.newline())
            .append(Component.text(normalizedCode, NamedTextColor.GRAY))
            .append(Component.newline());

        if (!normalizedLanguage.isEmpty()) {
            builder.append(Component.text("```", NamedTextColor.DARK_GRAY))
                .append(Component.newline());
        }

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

    private static String repeat(String value, int count) {
        if (count <= 0) {
            return "";
        }
        return value.repeat(count);
    }

    private record LinkParseResult(Component component, int nextIndex) {
    }

    private record InlineCodeParseResult(Component component, int nextIndex) {
    }

    private record EmphasisParseResult(Component component, int nextIndex) {
    }

    private record LineRenderResult(Component component, boolean listItem) {
    }
}
