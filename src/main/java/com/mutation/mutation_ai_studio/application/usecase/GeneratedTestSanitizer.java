package com.mutation.mutation_ai_studio.application.usecase;

final class GeneratedTestSanitizer {

    private GeneratedTestSanitizer() {
    }

    static String sanitize(String generatedCode) {
        if (generatedCode == null) {
            return "";
        }

        String sanitized = generatedCode.replace("\r\n", "\n").trim();
        sanitized = stripThinkingBlocks(sanitized);
        sanitized = stripMarkdownFences(sanitized);
        sanitized = stripLeadingNoise(sanitized);
        sanitized = sanitized.replace("```java", "");
        sanitized = sanitized.replace("```", "");

        int lastBrace = sanitized.lastIndexOf('}');
        if (lastBrace >= 0) {
            sanitized = sanitized.substring(0, lastBrace + 1);
        }

        return sanitized.trim();
    }

    /**
     * Removes thinking blocks used by reasoning models (e.g. qwen3, deepseek-r1).
     * Strips content between <think> and </think> tags before processing the code.
     */
    private static String stripThinkingBlocks(String text) {
        String result = text;
        while (true) {
            int start = result.indexOf("<think>");
            if (start < 0) {
                break;
            }
            int end = result.indexOf("</think>", start);
            if (end < 0) {
                result = result.substring(0, start).trim();
                break;
            }
            result = (result.substring(0, start) + result.substring(end + "</think>".length())).trim();
        }
        return result;
    }

    private static String stripMarkdownFences(String text) {
        String sanitized = text;
        String[] lines = sanitized.split("\n", -1);
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if ("```".equals(trimmed) || "```java".equalsIgnoreCase(trimmed)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString().trim();
    }

    private static String stripLeadingNoise(String text) {
        int packageIndex = text.indexOf("package ");
        int importIndex = text.indexOf("import ");
        int publicClassIndex = text.indexOf("public class ");
        int classIndex = text.indexOf("class ");

        int start = smallestPositiveIndex(packageIndex, importIndex, publicClassIndex, classIndex);
        if (start < 0) {
            return text;
        }

        return text.substring(start).trim();
    }

    private static int smallestPositiveIndex(int... indexes) {
        int smallest = Integer.MAX_VALUE;
        for (int index : indexes) {
            if (index >= 0 && index < smallest) {
                smallest = index;
            }
        }
        return smallest == Integer.MAX_VALUE ? -1 : smallest;
    }
}
