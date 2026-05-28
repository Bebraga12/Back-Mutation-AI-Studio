package com.mutation.mutation_ai_studio.adapters.in.web;

import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiMavenDetectionResult;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class MavenResolver {

    ApiMavenDetectionResult resolve(String preferredPath) {
        Set<String> candidates = new LinkedHashSet<>();

        if (!normalize(preferredPath).isBlank()) {
            candidates.add(normalize(preferredPath));
        }

        addEnvMavenCandidates(candidates);
        addWhereCommandCandidates(candidates, "mvn.cmd");
        addWhereCommandCandidates(candidates, "mvn");
        addMavenWrapperCandidates(candidates);

        for (String candidate : candidates) {
            Path path = Paths.get(candidate).toAbsolutePath().normalize();
            if (Files.exists(path) && Files.isRegularFile(path)) {
                String resolvedPath = path.toString();
                return new ApiMavenDetectionResult(
                        true,
                        resolvedPath,
                        resolveVersion(resolvedPath),
                        "Maven detectado automaticamente no ambiente local."
                );
            }
        }

        return new ApiMavenDetectionResult(
                false,
                "",
                "",
                "Maven nao encontrado automaticamente. Informe o caminho manualmente."
        );
    }

    private void addEnvMavenCandidates(Set<String> candidates) {
        addMavenFromHome(candidates, System.getenv("M2_HOME"));
        addMavenFromHome(candidates, System.getenv("MAVEN_HOME"));
    }

    private void addMavenFromHome(Set<String> candidates, String mavenHome) {
        String normalized = normalize(mavenHome);
        if (normalized.isBlank()) {
            return;
        }

        candidates.add(Paths.get(normalized, "bin", "mvn.cmd").toString());
        candidates.add(Paths.get(normalized, "bin", "mvn").toString());
    }

    private void addWhereCommandCandidates(Set<String> candidates, String command) {
        ProcessBuilder processBuilder = new ProcessBuilder("cmd", "/c", "where", command);
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String value = normalize(line);
                    if (!value.isBlank()) {
                        candidates.add(value);
                    }
                }
            }

            process.waitFor(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Ambiente sem where/mvn no PATH.
        }
    }

    private void addMavenWrapperCandidates(Set<String> candidates) {
        String userHome = normalize(System.getProperty("user.home"));
        if (userHome.isBlank()) {
            return;
        }

        Path wrapperRoot = Paths.get(userHome, ".m2", "wrapper", "dists");
        if (!Files.isDirectory(wrapperRoot)) {
            return;
        }

        try (Stream<Path> pathStream = Files.walk(wrapperRoot, 8)) {
            pathStream
                    .filter(Files::isRegularFile)
                    .filter(path -> "mvn.cmd".equalsIgnoreCase(path.getFileName().toString()))
                    .map(path -> path.toAbsolutePath().normalize().toString())
                    .forEach(candidates::add);
        } catch (Exception ignored) {
            // Ignora falhas de IO em varredura local.
        }
    }

    private String resolveVersion(String mavenPath) {
        Pattern versionPattern = Pattern.compile("apache-maven-(\\d+\\.\\d+\\.\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = versionPattern.matcher(mavenPath);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "desconhecida";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
