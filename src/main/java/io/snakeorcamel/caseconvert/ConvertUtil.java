package io.snakeorcamel.caseconvert;

public final class ConvertUtil {

    private ConvertUtil() {
    }

    public static String toCamelCase(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        StringBuilder result = new StringBuilder(text.length());
        boolean upperNext = false;
        boolean firstWritten = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '_' || c == '-') {
                if (firstWritten) {
                    upperNext = true;
                }
                continue;
            }
            if (!firstWritten) {
                result.append(Character.toLowerCase(c));
                firstWritten = true;
            } else if (upperNext) {
                result.append(Character.toUpperCase(c));
                upperNext = false;
            } else {
                result.append(Character.toLowerCase(c));
            }
        }
        return result.toString();
    }

    public static String toSnakeCase(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        StringBuilder result = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char cur = text.charAt(i);

            if (cur == '-') {
                appendSeparator(result);
                continue;
            }

            if (Character.isUpperCase(cur)) {
                if (needsSeparatorBefore(text, i, result)) {
                    appendSeparator(result);
                }
                result.append(Character.toLowerCase(cur));
            } else {
                result.append(cur);
            }
        }
        return result.toString();
    }

    private static boolean needsSeparatorBefore(String text, int i, StringBuilder result) {
        if (i == 0) {
            return false;
        }
        char prev = text.charAt(i - 1);
        boolean prevIsLowerOrDigit = Character.isLowerCase(prev) || Character.isDigit(prev);
        boolean endOfUpperRun = Character.isUpperCase(prev)
                && i + 1 < text.length()
                && Character.isLowerCase(text.charAt(i + 1));
        return prevIsLowerOrDigit || endOfUpperRun;
    }

    private static void appendSeparator(StringBuilder result) {
        if (result.length() == 0 || result.charAt(result.length() - 1) == '_') {
            return;
        }
        result.append('_');
    }
}
