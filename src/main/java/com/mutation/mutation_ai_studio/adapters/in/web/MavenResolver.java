package com.mutation.mutation_ai_studio.adapters.in.web;

import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiMavenDetectionResult;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class MavenResolver {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    ApiMavenDetectionResult resolve(String preferredPath) {
        Set<String> candidates = new LinkedHashSet<>();

        if (!normalize(preferredPath).isBlank()) {
            candidates.add(normalize(preferredPath));
        }

        addEnvMavenCandidates(candidates);
        addSystemMavenCandidates(candidates);
        addMavenWrapperCandidates(candidates);

        for (String candidate : candidates) {
            Path path = Paths.get(candidate).toAbsolutePath().normalize();
            if (Files.exists(path) && Files.isRegularFile(path) && Files.isExecutable(path)) {
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

        if (IS_WINDOWS) {
            candidates.add(Paths.get(normalized, "bin", "mvn.cmd").toString());
        }
        candidates.add(Paths.get(normalized, "bin", "mvn").toString());
    }

    private void addSystemMavenCandidates(Set<String> candidates) {
        if (IS_WINDOWS) {
            addProcessCandidates(candidates, "cmd", "/c", "where", "mvn.cmd");
            addProcessCandidates(candidates, "cmd", "/c", "where", "mvn");
        } else {
            addProcessCandidates(candidates, "which", "mvn");
            // Caminhos fixos comuns no Linux/Mac
            candidates.add("/usr/bin/mvn");
            candidates.add("/usr/local/bin/mvn");
            candidates.add("/opt/homebrew/bin/mvn");
        }
    }

    private void addProcessCandidates(Set<String> candidates, String... command) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
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
            // Comando nao disponivel no PATH.
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

        String mvnFileName = IS_WINDOWS ? "mvn.cmd" : "mvn";

        try (Stream<Path> pathStream = Files.walk(wrapperRoot, 8)) {
            pathStream
                    .filter(Files::isRegularFile)
                    .filter(Files::isExecutable)
                    .filter(path -> mvnFileName.equalsIgnoreCase(path.getFileName().toString()))
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
