package com.mutation.mutation_ai_studio.adapters.in.web;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

final class PitestReportLoader {

    Optional<PitestMetrics> loadLatestMetrics(Path repositoryRoot) {
        Path pitReports = repositoryRoot.resolve("target").resolve("pit-reports");
        if (!Files.isDirectory(pitReports)) {
            return Optional.empty();
        }

        try (Stream<Path> reportStream = Files.walk(pitReports, 5)) {
            Optional<Path> latestReport = reportStream
                    .filter(Files::isRegularFile)
                    .filter(path -> "mutations.xml".equalsIgnoreCase(path.getFileName().toString()))
                    .max(Comparator.comparing(this::lastModifiedTimeSafe));

            if (latestReport.isEmpty()) {
                return Optional.empty();
            }

            return parsePitestMetrics(latestReport.get());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    boolean hasPitestPlugin(Path repositoryRoot) {
        Path pomFile = repositoryRoot.resolve("pom.xml");
        if (!Files.exists(pomFile)) {
            return false;
        }

        try {
            String pomContent = Files.readString(pomFile, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            return pomContent.contains("pitest-maven") || pomContent.contains("org.pitest");
        } catch (Exception ignored) {
            return false;
        }
    }

    private Optional<PitestMetrics> parsePitestMetrics(Path reportFile) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(reportFile.toFile());
            NodeList mutationNodes = document.getElementsByTagName("mutation");

            int total = mutationNodes.getLength();
            if (total == 0) {
                return Optional.of(new PitestMetrics(0, 0, 0, 0, 0, 0, 0, reportFile.toAbsolutePath().toString()));
            }

            int killed = 0;
            int survived = 0;
            int noCoverage = 0;

            for (int index = 0; index < mutationNodes.getLength(); index++) {
                Node node = mutationNodes.item(index);
                if (!(node instanceof Element element)) {
                    continue;
                }

                String status = normalize(element.getAttribute("status")).toUpperCase(Locale.ROOT);
                switch (status) {
                    case "KILLED" -> killed++;
                    case "SURVIVED" -> survived++;
                    case "NO_COVERAGE" -> noCoverage++;
                    default -> { /* TIMED_OUT, RUN_ERROR etc. ignorados na contagem */ }
                }
            }

            int survivorCount = survived;
            int mutationScore = total > 0 ? Math.round((killed * 100f) / total) : 0;
            int survivorRate = total > 0 ? Math.round((survivorCount * 100f) / total) : 0;
            int killedRate = total > 0 ? Math.round((killed * 100f) / total) : 0;

            return Optional.of(new PitestMetrics(
                    mutationScore,
                    total,
                    killed,
                    survivorCount,
                    noCoverage,
                    survivorRate,
                    killedRate,
                    reportFile.toAbsolutePath().toString()
            ));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private FileTime lastModifiedTimeSafe(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (Exception ignored) {
            return FileTime.from(Instant.EPOCH);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
