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

    ApiMavenDetectionResult resolve(String preferredPath, String repositoryPath) {
        Set<String> candidates = new LinkedHashSet<>();

        // 1. mvnw do próprio projeto (mais confiável — mesma versão do build)
        if (!normalize(repositoryPath).isBlank()) {
            Path repo = Paths.get(repositoryPath).toAbsolutePath().normalize();
            if (IS_WINDOWS) {
                candidates.add(repo.resolve("mvnw.cmd").toString());
            }
            candidates.add(repo.resolve("mvnw").toString());
        }

        // 2. Caminho informado manualmente
        if (!normalize(preferredPath).isBlank()) {
            candidates.add(normalize(preferredPath));
        }

        // 3. Variáveis de ambiente M2_HOME / MAVEN_HOME
        addEnvMavenCandidates(candidates);

        // 4. which/where no PATH
        addSystemMavenCandidates(candidates);

        // 5. Cache do Maven Wrapper em ~/.m2/wrapper/dists
        addMavenWrapperCandidates(candidates);

        for (String candidate : candidates) {
            Path path = Paths.get(candidate).toAbsolutePath().normalize();
            if (Files.exists(path) && Files.isRegularFile(path) && Files.isExecutable(path)) {
                String resolvedPath = path.toString();
                String version = resolveVersionByRunning(resolvedPath);
                boolean isWrapper = resolvedPath.endsWith("mvnw") || resolvedPath.endsWith("mvnw.cmd");
                String message = isWrapper
                        ? "Maven Wrapper do projeto detectado (./mvnw). Versão: " + version + "."
                        : "Maven detectado automaticamente no ambiente local. Versão: " + version + ".";
                return new ApiMavenDetectionResult(true, resolvedPath, version, message);
            }
        }

        return new ApiMavenDetectionResult(
                false, "", "",
                "Maven nao encontrado. Informe o caminho manualmente ou garanta que mvnw existe no repositorio.");
    }

    private void addEnvMavenCandidates(Set<String> candidates) {
        addMavenFromHome(candidates, System.getenv("M2_HOME"));
        addMavenFromHome(candidates, System.getenv("MAVEN_HOME"));
    }

    private void addMavenFromHome(Set<String> candidates, String mavenHome) {
        String normalized = normalize(mavenHome);
        if (normalized.isBlank()) return;
        if (IS_WINDOWS) candidates.add(Paths.get(normalized, "bin", "mvn.cmd").toString());
        candidates.add(Paths.get(normalized, "bin", "mvn").toString());
    }

    private void addSystemMavenCandidates(Set<String> candidates) {
        if (IS_WINDOWS) {
            addProcessCandidates(candidates, "cmd", "/c", "where", "mvn.cmd");
            addProcessCandidates(candidates, "cmd", "/c", "where", "mvn");
        } else {
            addProcessCandidates(candidates, "which", "mvn");
            candidates.add("/usr/bin/mvn");
            candidates.add("/usr/local/bin/mvn");
            candidates.add("/opt/homebrew/bin/mvn");
        }
    }

    private void addProcessCandidates(Set<String> candidates, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String value = normalize(line);
                    if (!value.isBlank()) candidates.add(value);
                }
            }
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    private void addMavenWrapperCandidates(Set<String> candidates) {
        String userHome = normalize(System.getProperty("user.home"));
        if (userHome.isBlank()) return;

        Path wrapperRoot = Paths.get(userHome, ".m2", "wrapper", "dists");
        if (!Files.isDirectory(wrapperRoot)) return;

        String mvnFileName = IS_WINDOWS ? "mvn.cmd" : "mvn";
        try (Stream<Path> pathStream = Files.walk(wrapperRoot, 8)) {
            pathStream
                    .filter(Files::isRegularFile)
                    .filter(Files::isExecutable)
                    .filter(path -> mvnFileName.equalsIgnoreCase(path.getFileName().toString()))
                    .map(path -> path.toAbsolutePath().normalize().toString())
                    .forEach(candidates::add);
        } catch (Exception ignored) {}
    }

    private String resolveVersionByRunning(String mavenPath) {
        // Tenta extrair versão do caminho primeiro (mais rápido)
        Pattern pathPattern = Pattern.compile("apache-maven-(\\d+\\.\\d+\\.\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher pathMatcher = pathPattern.matcher(mavenPath);
        if (pathMatcher.find()) return pathMatcher.group(1);

        // Executa o binário com --version
        try {
            ProcessBuilder pb = new ProcessBuilder(mavenPath, "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                Pattern versionPattern = Pattern.compile("(\\d+\\.\\d+\\.\\d+)");
                while ((line = reader.readLine()) != null) {
                    Matcher m = versionPattern.matcher(line);
                    if (m.find()) return m.group(1);
                }
            }
            process.waitFor(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {}

        return "desconhecida";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
