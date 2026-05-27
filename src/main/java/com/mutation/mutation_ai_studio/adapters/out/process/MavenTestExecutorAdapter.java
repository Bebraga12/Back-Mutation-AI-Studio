package com.mutation.mutation_ai_studio.adapters.out.process;

import com.mutation.mutation_ai_studio.application.port.out.TestExecutorPort;
import com.mutation.mutation_ai_studio.domain.model.TestExecutionFeedback;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class MavenTestExecutorAdapter implements TestExecutorPort {

    @Override
    public TestExecutionFeedback execute(Path projectRoot, String testClassName) {
        ProcessBuilder processBuilder = new ProcessBuilder(
                "./mvnw",
                "-Dtest=" + testClassName,
                "-Djacoco.skip=true",
                "compile",
                "test"
        );
        processBuilder.directory(projectRoot.toFile());
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
            String output = readOutput(process);
            int exitCode = process.waitFor();
            boolean passed = exitCode == 0;
            List<String> errors = passed ? List.of() : extractErrors(output);
            return new TestExecutionFeedback(passed, exitCode, errors, output);
        } catch (IOException e) {
            return new TestExecutionFeedback(false, -1, List.of("erro ao iniciar execução Maven: " + e.getMessage()), "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TestExecutionFeedback(false, -1, List.of("execução Maven interrompida"), "");
        }
    }

    private String readOutput(Process process) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!output.isEmpty()) {
                    output.append(System.lineSeparator());
                }
                output.append(line);
            }
        }
        return output.toString();
    }

    private List<String> extractErrors(String output) {
        List<String> errors = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String normalized = line.trim();
            if (normalized.isBlank()) {
                continue;
            }

            String lower = normalized.toLowerCase(Locale.ROOT);
            if (lower.contains("[error]")
                    || lower.contains("compilation failure")
                    || lower.contains("failed tests")
                    || lower.contains("there are test failures")
                    || lower.contains("cannot find symbol")
                    || lower.contains("assertionfailederror")
                    || lower.contains("wanted but not invoked")
                    || lower.contains("expected:")
                    || lower.contains("but was:")) {
                errors.add(normalized);
            }
        }

        if (errors.isEmpty()) {
            errors.add("execução Maven falhou, mas nenhuma linha de erro relevante foi extraída");
        }

        return errors.stream().limit(20).toList();
    }
}
