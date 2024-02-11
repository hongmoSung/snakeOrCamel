package io.snakeorcamel;

public class ConvertUtil {

    public static String toCamelCase(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        StringBuilder result = new StringBuilder();
        boolean toUpper = false;
        for (char currentChar : text.toCharArray()) {
            if (currentChar == '_' || currentChar == '-') {
                toUpper = true;
            } else {
                if (toUpper) {
                    result.append(Character.toUpperCase(currentChar));
                    toUpper = false;
                } else {
                    result.append(Character.toLowerCase(currentChar));
                }
            }
        }
        return result.toString();
    }

    public static String toSnakeCase(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        StringBuilder result = new StringBuilder();
        char prevChar = '\0';
        for (char currentChar : text.toCharArray()) {
            if (Character.isUpperCase(currentChar) && prevChar != '\0' && !Character.isUpperCase(prevChar)) {
                result.append('_');
            }
            result.append(Character.toLowerCase(currentChar));
            prevChar = currentChar;
        }
        return result.toString();
    }
}
