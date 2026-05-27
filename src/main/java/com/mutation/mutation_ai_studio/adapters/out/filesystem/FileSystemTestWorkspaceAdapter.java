package com.mutation.mutation_ai_studio.adapters.out.filesystem;

import com.mutation.mutation_ai_studio.application.port.out.TestWorkspacePort;
import com.mutation.mutation_ai_studio.domain.model.GeneratedTestCandidate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

@Component
public class FileSystemTestWorkspaceAdapter implements TestWorkspacePort {

    @Override
    public Path writeCandidate(Path projectRoot, GeneratedTestCandidate candidate) {
        Path testRoot = projectRoot.resolve("src/test/java");
        Path packageDirectory = resolvePackageDirectory(testRoot, candidate.sourceCode());
        Path testFile = packageDirectory.resolve(candidate.testClassName() + ".java");

        try {
            Files.createDirectories(packageDirectory);
            Files.writeString(testFile, candidate.sourceCode());
            return testFile;
        } catch (IOException e) {
            throw new IllegalStateException("Erro ao escrever teste candidato em workspace: " + testFile, e);
        }
    }

    @Override
    public void cleanup(Path candidatePath) {
        if (candidatePath == null) {
            return;
        }
        try {
            Files.deleteIfExists(candidatePath);
            deleteEmptyParentDirectories(candidatePath.getParent());
        } catch (IOException ignored) {
        }
    }

    private void deleteEmptyParentDirectories(Path directory) {
        while (directory != null) {
            String normalized = directory.toString().replace('\\', '/');
            if (normalized.endsWith("src/test/java")) {
                break;
            }
            try (Stream<Path> entries = Files.list(directory)) {
                if (entries.findAny().isPresent()) {
                    break;
                }
            } catch (IOException e) {
                break;
            }
            try {
                Files.deleteIfExists(directory);
            } catch (IOException ignored) {
                break;
            }
            directory = directory.getParent();
        }
    }

    private Path resolvePackageDirectory(Path testRoot, String sourceCode) {
        String packageName = extractPackageName(sourceCode);
        if (packageName.isBlank()) {
            return testRoot;
        }
        return testRoot.resolve(packageName.replace('.', '/'));
    }

    private String extractPackageName(String sourceCode) {
        for (String line : sourceCode.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ") && trimmed.endsWith(";")) {
                return trimmed.substring("package ".length(), trimmed.length() - 1).trim();
            }
        }
        return "";
    }
}
