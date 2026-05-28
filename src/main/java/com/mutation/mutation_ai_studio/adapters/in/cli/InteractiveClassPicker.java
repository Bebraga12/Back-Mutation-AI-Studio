package com.mutation.mutation_ai_studio.adapters.in.cli;

import com.mutation.mutation_ai_studio.domain.model.JavaClassCandidate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

class InteractiveClassPicker {

    private final String modelName;

    InteractiveClassPicker(String modelName) {
        this.modelName = modelName;
    }

    List<JavaClassCandidate> pick(List<JavaClassCandidate> all, Consumer<List<JavaClassCandidate>> onSelected) {
        printList(all);
        printModelHint(all);
        return readSelection(all, onSelected);
    }

    private void printList(List<JavaClassCandidate> classes) {
        System.out.println();
        System.out.printf("Classes encontradas no projeto (%d):%n%n", classes.size());
        System.out.printf("  %-4s  %-40s  %-18s  %s%n", "Nº", "Classe", "Tipo", "Custo");
        System.out.println("  " + "─".repeat(78));
        for (int i = 0; i < classes.size(); i++) {
            JavaClassCandidate c = classes.get(i);
            Cost cost = cost(c);
            System.out.printf("  %-4d  %-40s  %-18s  %s%n",
                    i + 1, c.className(), typeLabel(c), cost.label());
        }
        System.out.println();
    }

    private void printModelHint(List<JavaClassCandidate> all) {
        long low  = all.stream().filter(c -> cost(c) == Cost.LOW).count();
        long med  = all.stream().filter(c -> cost(c) == Cost.MEDIUM).count();
        long high = all.size() - low - med;
        int  size = extractModelSize(modelName);

        String hint;
        if (size > 0 && size <= 7) {
            hint = String.format("O modelo %s (%dB) é mais eficiente com classes de baixo e médio custo.", modelName, size);
        } else if (size <= 14) {
            hint = String.format("O modelo %s (%dB) equilibra bem todas as faixas de custo computacional.", modelName, size);
        } else if (size > 14) {
            hint = String.format("O modelo %s (%dB) suporta bem classes de qualquer custo computacional.", modelName, size);
        } else {
            hint = String.format("Modelo configurado: %s.", modelName);
        }

        System.out.printf("  %s%s%s%n", Ansi.GRAY, hint, Ansi.RESET);
        System.out.printf("  %sDisponíveis: %d baixo · %d médio · %d alto%s%n%n",
                Ansi.GRAY, low, med, high, Ansi.RESET);
    }

    private int extractModelSize(String model) {
        String lower = model.toLowerCase(Locale.ROOT);
        // match patterns like "7b", "14b", "32b", "70b"
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)b").matcher(lower);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private List<JavaClassCandidate> readSelection(List<JavaClassCandidate> all, Consumer<List<JavaClassCandidate>> onSelected) {
        // Do not close the Scanner — closing it closes System.in.
        Scanner scanner = new Scanner(System.in); // NOSONAR
        while (true) {
            System.out.print("→ ");
            String input = scanner.nextLine().trim().replaceAll("\\s+", ",");

            if (input.isEmpty()) {
                showSpinnerAndPrint(all, onSelected);
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
                showSpinnerAndPrint(selected, onSelected);
                return selected;
            } catch (IllegalArgumentException e) {
                System.out.printf("  %sEntrada inválida: %s%s%n%n",
                        Ansi.YELLOW, e.getMessage(), Ansi.RESET);
            }
        }
    }

    private void showSpinnerAndPrint(List<JavaClassCandidate> selected, Consumer<List<JavaClassCandidate>> work) {
        String[] frames = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
        AtomicBoolean done = new AtomicBoolean(false);

        Thread spinnerThread = new Thread(() -> {
            int i = 0;
            while (!done.get()) {
                System.out.printf("\r  %s Aplicando seleção...", frames[i++ % frames.length]);
                System.out.flush();
                try { Thread.sleep(80); } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        spinnerThread.setDaemon(true);
        spinnerThread.start();

        work.accept(selected);
        done.set(true);
        try { spinnerThread.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

        System.out.printf("\r%-40s%n", "");

        int nameWidth = selected.stream().mapToInt(c -> c.className().length()).max().orElse(20);
        System.out.println();
        selected.forEach(c -> System.out.printf(
                "  %-" + nameWidth + "s  %s%s%s%n",
                c.className(), Ansi.GRAY, c.packageName(), Ansi.RESET));
        System.out.println();
    }

    private Set<Integer> parseSelection(String input, int max) {
        Set<Integer> result = new LinkedHashSet<>();
        for (String part : input.split(",")) {
            part = part.trim();
            if (part.isBlank()) continue;
            if (part.contains("-")) {
                String[] range = part.split("-", 2);
                int from = Integer.parseInt(range[0].trim());
                int to   = Integer.parseInt(range[1].trim());
                if (from < 1 || to > max || from > to) {
                    throw new IllegalArgumentException(
                            "intervalo " + part + " fora do permitido (1-" + max + ")");
                }
                for (int i = from; i <= to; i++) result.add(i);
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

    // ── Cost heuristic ────────────────────────────────────────────────────────

    private enum Cost {
        LOW   ("●○○ baixo"),
        MEDIUM("●●○ médio"),
        HIGH  ("●●● alto");

        private final String label;
        Cost(String label) { this.label = label; }
        String label()     { return label; }
    }

    private Cost cost(JavaClassCandidate c) {
        String name = c.className().toLowerCase(Locale.ROOT);
        String pkg  = c.packageName() == null ? "" : c.packageName().toLowerCase(Locale.ROOT);

        if (name.contains("filter")
                || name.contains("jwt")
                || (name.contains("security") && !name.endsWith("service"))
                || name.endsWith("config")
                || pkg.endsWith(".config")
                || (name.contains("exception") && name.contains("handler"))
                || name.contains("advice")) {
            return Cost.HIGH;
        }
        if (name.endsWith("controller")) return Cost.MEDIUM;
        if (name.endsWith("service") || name.endsWith("repository")) return Cost.LOW;
        if (name.contains("exception") || name.contains("handler")) return Cost.HIGH;
        return Cost.MEDIUM;
    }

    private String typeLabel(JavaClassCandidate c) {
        String name = c.className().toLowerCase(Locale.ROOT);
        if (name.endsWith("controller"))  return "@RestController";
        if (name.endsWith("service"))     return "@Service";
        if (name.endsWith("repository"))  return "@Repository";
        if (name.contains("filter"))      return "Filtro/Security";
        if (name.contains("exception") || name.contains("handler") || name.contains("advice"))
            return "@ControllerAdvice";
        if (name.contains("jwt") || name.endsWith("config") || name.contains("security"))
            return "@Configuration";
        return "Classe";
    }
}
