package com.example.agentx.domain.prompt;

/** Prompt XML 转义工具 */
public final class PromptXmlUtils {

    private PromptXmlUtils() {
    }

    public static String escapeXml(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        StringBuilder escaped = new StringBuilder(text.length());
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);

            switch (codePoint) {
                case '&':
                    escaped.append("&amp;");
                    break;
                case '<':
                    escaped.append("&lt;");
                    break;
                case '>':
                    escaped.append("&gt;");
                    break;
                case '"':
                    escaped.append("&quot;");
                    break;
                case '\'':
                    escaped.append("&apos;");
                    break;
                default:
                    if (isValidXmlCodePoint(codePoint)) {
                        escaped.appendCodePoint(codePoint);
                    }
                    break;
            }
        }
        return escaped.toString();
    }

    private static boolean isValidXmlCodePoint(int codePoint) {
        return codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD
                || codePoint >= 0x20 && codePoint <= 0xD7FF
                || codePoint >= 0xE000 && codePoint <= 0xFFFD
                || codePoint >= 0x10000 && codePoint <= 0x10FFFF;
    }
}
