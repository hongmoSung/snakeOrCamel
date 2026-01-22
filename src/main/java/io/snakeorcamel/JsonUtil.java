package io.snakeorcamel;

import com.google.gson.*;

public class JsonUtil {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    public static String prettify(String json) {
        if (json == null || json.trim().isEmpty()) {
            return "";
        }

        try {
            JsonElement jsonElement = JsonParser.parseString(json);
            return GSON.toJson(jsonElement);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e);
        }
    }

    public static boolean isValidJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return false;
        }

        try {
            JsonParser.parseString(json);
            return true;
        } catch (JsonSyntaxException e) {
            return false;
        }
    }

    public static int getArrayCount(String json) {
        if (json == null || json.trim().isEmpty()) {
            return -1;
        }

        try {
            JsonElement jsonElement = JsonParser.parseString(json);
            if (jsonElement.isJsonArray()) {
                JsonArray array = jsonElement.getAsJsonArray();
                return array.size();
            }
            return -1;
        } catch (JsonSyntaxException e) {
            return -1;
        }
    }
}
