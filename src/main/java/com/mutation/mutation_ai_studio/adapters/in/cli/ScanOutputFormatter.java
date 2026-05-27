package com.mutation.mutation_ai_studio.adapters.in.cli;

import com.mutation.mutation_ai_studio.domain.model.JavaClassCandidate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ScanOutputFormatter {

    private static final List<ScanCategory> ORDER = List.of(
            ScanCategory.SERVICE,
            ScanCategory.CORE,
            ScanCategory.CONTROLLER,
            ScanCategory.OTHER,
            ScanCategory.DTO,
            ScanCategory.ENTITY,
            ScanCategory.REPOSITORY,
            ScanCategory.CONFIG,
            ScanCategory.SECURITY
    );

    void print(Path projectRoot,
               List<JavaClassCandidate> classes,
               boolean verbose,
               boolean focusTestable,
               ScanCategory onlyCategory) {

        if (classes.isEmpty()) {
            System.out.printf("Projeto: %s%n", projectRoot);
            System.out.println("0 classes encontradas");
            System.out.println("Nenhuma classe Java encontrada em src/main/java.");
            return;
        }

        Map<ScanCategory, List<JavaClassCandidate>> grouped = groupByCategory(classes);

        if (onlyCategory != null) {
            printOnlyCategory(projectRoot, classes, grouped, onlyCategory, verbose);
            return;
        }

        if (verbose) {
            printVerbose(projectRoot, classes, grouped);
            return;
        }

        if (focusTestable) {
            printFocusTestable(projectRoot, classes, grouped);
            return;
        }

        printDefault(projectRoot, classes, grouped);
    }

    private Map<ScanCategory, List<JavaClassCandidate>> groupByCategory(List<JavaClassCandidate> classes) {
        Map<ScanCategory, List<JavaClassCandidate>> grouped = new EnumMap<>(ScanCategory.class);
        for (ScanCategory category : ScanCategory.values()) {
            grouped.put(category, new ArrayList<>());
        }

        classes.stream()
                .sorted(Comparator.comparing(JavaClassCandidate::fullyQualifiedName))
                .forEach(candidate -> grouped.get(classify(candidate)).add(candidate));

        return grouped;
    }

    private void printOnlyCategory(Path projectRoot,
                                   List<JavaClassCandidate> allClasses,
                                   Map<ScanCategory, List<JavaClassCandidate>> grouped,
                                   ScanCategory onlyCategory,
                                   boolean verbose) {

        List<JavaClassCandidate> selected = grouped.get(onlyCategory);

        System.out.printf("Projeto: %s%n", projectRoot);
        System.out.printf("%d classes encontradas | filtro: %s (%d)%n%n",
                allClasses.size(),
                onlyCategory.key(),
                selected.size());

        if (selected.isEmpty()) {
            System.out.println("Nenhuma classe encontrada para essa categoria.");
            return;
        }

        if (verbose) {
            selected.forEach(candidate -> System.out.printf(" - %s | %s | %s%n",
                    candidate.className(),
                    candidate.fullyQualifiedName(),
                    candidate.relativePath()));
            return;
        }

        selected.stream()
                .map(JavaClassCandidate::className)
                .forEach(name -> System.out.printf(" - %s%n", name));

        System.out.println();
        System.out.println("Use --verbose para ver FQCN e path.");
    }

    private void printDefault(Path projectRoot, List<JavaClassCandidate> classes, Map<ScanCategory, List<JavaClassCandidate>> grouped) {
        System.out.printf("Projeto: %s%n", projectRoot);
        System.out.printf("%d classes encontradas%n%n", classes.size());

        boolean hasHigh = !grouped.get(ScanCategory.SERVICE).isEmpty() || !grouped.get(ScanCategory.CORE).isEmpty();
        if (hasHigh) {
            System.out.println("Alta prioridade para testes");
            printCategoryWithNamesIfNotEmpty(grouped, ScanCategory.SERVICE);
            printCategoryWithNamesIfNotEmpty(grouped, ScanCategory.CORE);
        }

        if (!grouped.get(ScanCategory.CONTROLLER).isEmpty()) {
            System.out.println("Média prioridade");
            printCategoryWithNames(grouped, ScanCategory.CONTROLLER);
        }

        int lowCount = grouped.get(ScanCategory.DTO).size()
                + grouped.get(ScanCategory.ENTITY).size()
                + grouped.get(ScanCategory.REPOSITORY).size()
                + grouped.get(ScanCategory.CONFIG).size()
                + grouped.get(ScanCategory.SECURITY).size();
        if (lowCount > 0) {
            System.out.println("Baixa prioridade");
            printCategoryCountOnlyIfNotEmpty(grouped, ScanCategory.DTO);
            printCategoryCountOnlyIfNotEmpty(grouped, ScanCategory.ENTITY);
            printCategoryCountOnlyIfNotEmpty(grouped, ScanCategory.REPOSITORY);
            int configSecCount = grouped.get(ScanCategory.CONFIG).size() + grouped.get(ScanCategory.SECURITY).size();
            if (configSecCount > 0) {
                System.out.printf(" Config/Security (%d)%n", configSecCount);
            }
        }

        System.out.println();
        System.out.println("Use --verbose para listar todas as classes.");
        System.out.println("Use --focus testable para ver apenas classes mais testáveis.");
        System.out.println("Use scan <categoria> para filtrar (service, core, controller, repository, entity, dto, config, security, other).");
    }

    private void printFocusTestable(Path projectRoot, List<JavaClassCandidate> classes, Map<ScanCategory, List<JavaClassCandidate>> grouped) {
        List<JavaClassCandidate> focused = new ArrayList<>();
        focused.addAll(grouped.get(ScanCategory.SERVICE));
        focused.addAll(grouped.get(ScanCategory.CORE));

        System.out.printf("Projeto: %s%n", projectRoot);
        System.out.printf("%d classes encontradas | %d classes com foco testável%n%n", classes.size(), focused.size());

        System.out.println("Foco: classes mais relevantes para geração de testes");
        printCategoryWithNames(grouped, ScanCategory.SERVICE);
        printCategoryWithNames(grouped, ScanCategory.CORE);

        System.out.println("Use --verbose para ver a listagem completa por categoria.");
    }

    private void printVerbose(Path projectRoot, List<JavaClassCandidate> classes, Map<ScanCategory, List<JavaClassCandidate>> grouped) {
        System.out.printf("Projeto: %s%n", projectRoot);
        System.out.printf("%d classes encontradas%n%n", classes.size());

        System.out.println("Resumo por categoria:");
        for (ScanCategory category : ORDER) {
            int count = grouped.get(category).size();
            System.out.printf(" - %s: %d%n", category.key(), count);
        }

        System.out.println();
        for (ScanCategory category : ORDER) {
            List<JavaClassCandidate> candidates = grouped.get(category);
            if (candidates.isEmpty()) {
                continue;
            }

            System.out.printf("%s (%d)%n", category.key(), candidates.size());
            candidates.forEach(candidate -> System.out.printf(" - %s | %s | %s%n",
                    candidate.className(),
                    candidate.fullyQualifiedName(),
                    candidate.relativePath()));
            System.out.println();
        }
    }

    private void printCategoryWithNames(Map<ScanCategory, List<JavaClassCandidate>> grouped, ScanCategory category) {
        List<JavaClassCandidate> candidates = grouped.get(category);
        System.out.printf(" %s (%d)%n", category.label(), candidates.size());
        candidates.stream()
                .map(JavaClassCandidate::className)
                .forEach(name -> System.out.printf(" - %s%n", name));
    }

    private void printCategoryWithNamesIfNotEmpty(Map<ScanCategory, List<JavaClassCandidate>> grouped, ScanCategory category) {
        if (!grouped.get(category).isEmpty()) {
            printCategoryWithNames(grouped, category);
        }
    }

    private void printCategoryCountOnly(Map<ScanCategory, List<JavaClassCandidate>> grouped, ScanCategory category) {
        System.out.printf(" %s (%d)%n", category.label(), grouped.get(category).size());
    }

    private void printCategoryCountOnlyIfNotEmpty(Map<ScanCategory, List<JavaClassCandidate>> grouped, ScanCategory category) {
        if (!grouped.get(category).isEmpty()) {
            printCategoryCountOnly(grouped, category);
        }
    }

    private ScanCategory classify(JavaClassCandidate candidate) {
        String className = candidate.className().toLowerCase(Locale.ROOT);
        String fqcn = candidate.fullyQualifiedName().toLowerCase(Locale.ROOT);
        String path = candidate.relativePath().toLowerCase(Locale.ROOT);
        String full = String.join(" ", className, fqcn, path);

        if (containsAny(full, "service")) {
            return ScanCategory.SERVICE;
        }
        if (containsAny(full, "controller")) {
            return ScanCategory.CONTROLLER;
        }
        if (containsAny(full, "repository", "repo")) {
            return ScanCategory.REPOSITORY;
        }
        if (containsAny(full, "entity")) {
            return ScanCategory.ENTITY;
        }
        if (containsAny(full, "dto", "request", "response")) {
            return ScanCategory.DTO;
        }
        if (containsAny(full, "security", "auth", "jwt")) {
            return ScanCategory.SECURITY;
        }
        if (containsAny(full, "config", "configuration", "properties")) {
            return ScanCategory.CONFIG;
        }
        if (containsAny(full, "core", "generator", "renderer", "validator", "calculator", "facade", "usecase", "use_case", "use-case")) {
            return ScanCategory.CORE;
        }

        return ScanCategory.OTHER;
    }

    private boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
