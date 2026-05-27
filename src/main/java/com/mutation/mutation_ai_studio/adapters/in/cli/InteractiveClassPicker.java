package com.mutation.mutation_ai_studio.adapters.in.cli;

import com.mutation.mutation_ai_studio.domain.model.JavaClassCandidate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;

class InteractiveClassPicker {

    List<JavaClassCandidate> pick(List<JavaClassCandidate> all) {
        printList(all);
        printRecommendation(all);
        return readSelection(all);
    }

    private void printList(List<JavaClassCandidate> classes) {
        System.out.println();
        System.out.printf("Classes encontradas no projeto (%d):%n%n", classes.size());
        System.out.printf("  %-4s  %-40s  %-18s  %s%n", "Nº", "Classe", "Tipo", "Dificuldade");
        System.out.println("  " + "─".repeat(78));
        for (int i = 0; i < classes.size(); i++) {
            JavaClassCandidate c = classes.get(i);
            Complexity cx = complexity(c);
            String type = typeLabel(c);
            String rec = cx == Complexity.BAIXA ? "  ← recomendada" : "";
            System.out.printf("  %-4d  %-40s  %-18s  %s%s%n",
                    i + 1, c.className(), type, cx.label(), rec);
        }
        System.out.println();
    }

    private void printRecommendation(List<JavaClassCandidate> all) {
        long baixa = all.stream().filter(c -> complexity(c) == Complexity.BAIXA).count();
        long media = all.stream().filter(c -> complexity(c) == Complexity.MEDIA).count();
        System.out.printf("  Dica: modelos pequenos (7B) geram melhores testes para classes BAIXA e MÉDIA.%n");
        System.out.printf("  Disponíveis: %d BAIXA + %d MÉDIA + %d ALTA%n", baixa, media,
                all.size() - baixa - media);
        System.out.println();
    }

    private List<JavaClassCandidate> readSelection(List<JavaClassCandidate> all) {
        // Do not close the Scanner — closing it closes System.in, which would break
        // subsequent reads in the same JVM process.
        Scanner scanner = new Scanner(System.in); // NOSONAR
        while (true) {
            System.out.println("Selecione as classes desejadas:");
            System.out.println("  → Números separados por vírgula  (ex: 1,3,5)");
            System.out.println("  → Intervalo                      (ex: 1-5)");
            System.out.println("  → Combinação                     (ex: 1-3,7,9)");
            System.out.println("  → Enter sem digitar              (seleciona todas)");
            System.out.println("  → 0                              (cancela)");
            System.out.print("Sua escolha: ");

            String input = scanner.nextLine().trim();

            if (input.isEmpty()) {
                System.out.printf("%nTodas as %d classes selecionadas.%n%n", all.size());
                return all;
            }

            if ("0".equals(input)) {
                System.out.println("Operação cancelada.");
                System.exit(0);
            }

            try {
                Set<Integer> indices = parseSelection(input, all.size());
                List<JavaClassCandidate> selected = new ArrayList<>();
                for (int idx : new TreeSet<>(indices)) {
                    selected.add(all.get(idx - 1));
                }
                System.out.printf("%n%d classe(s) selecionada(s):%n", selected.size());
                selected.forEach(c -> System.out.printf("  - %s  [%s]%n",
                        c.className(), complexity(c).label()));
                System.out.println();
                return selected;
            } catch (IllegalArgumentException e) {
                System.out.println("  Entrada inválida: " + e.getMessage() + ". Tente novamente.");
                System.out.println();
            }
        }
    }

    private Set<Integer> parseSelection(String input, int max) {
        Set<Integer> result = new LinkedHashSet<>();
        for (String part : input.split(",")) {
            part = part.trim();
            if (part.contains("-")) {
                String[] range = part.split("-", 2);
                int from = Integer.parseInt(range[0].trim());
                int to = Integer.parseInt(range[1].trim());
                if (from < 1 || to > max || from > to) {
                    throw new IllegalArgumentException(
                            "intervalo " + part + " fora do permitido (1-" + max + ")");
                }
                for (int i = from; i <= to; i++) {
                    result.add(i);
                }
            } else {
                int n = Integer.parseInt(part);
                if (n < 1 || n > max) {
                    throw new IllegalArgumentException(
                            "número " + n + " fora do permitido (1-" + max + ")");
                }
                result.add(n);
            }
        }
        return result;
    }

    // ── Complexity heuristic (based on class name / package conventions) ──────

    private enum Complexity {
        BAIXA("★☆☆ BAIXA"),
        MEDIA("★★☆ MÉDIA"),
        ALTA("★★★ ALTA");

        private final String label;

        Complexity(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    private Complexity complexity(JavaClassCandidate c) {
        String name = c.className().toLowerCase();
        String pkg = c.packageName() == null ? "" : c.packageName().toLowerCase();

        if (name.contains("filter")
                || name.contains("jwt")
                || (name.contains("security") && !name.endsWith("service"))
                || name.endsWith("config")
                || pkg.endsWith(".config")
                || name.contains("exception") && name.contains("handler")
                || name.contains("advice")) {
            return Complexity.ALTA;
        }

        if (name.endsWith("controller")) {
            return Complexity.MEDIA;
        }

        if (name.endsWith("service") || name.endsWith("repository")) {
            return Complexity.BAIXA;
        }

        if (name.contains("exception") || name.contains("handler")) {
            return Complexity.ALTA;
        }

        return Complexity.MEDIA;
    }

    private String typeLabel(JavaClassCandidate c) {
        String name = c.className().toLowerCase();
        if (name.endsWith("controller")) return "@RestController";
        if (name.endsWith("service")) return "@Service";
        if (name.endsWith("repository")) return "@Repository";
        if (name.contains("filter")) return "Filtro/Security";
        if (name.contains("exception") || name.contains("handler") || name.contains("advice")) return "@ControllerAdvice";
        if (name.contains("jwt") || name.endsWith("config") || name.contains("security")) return "@Configuration";
        return "Classe";
    }
}
