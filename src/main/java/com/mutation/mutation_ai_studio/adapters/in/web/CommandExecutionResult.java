package com.mutation.mutation_ai_studio.adapters.in.web;

import java.util.List;

record CommandExecutionResult(
        List<String> command,
        int exitCode,
        boolean timedOut,
        long durationMs,
        List<String> outputLines
) {
}
