package com.mutation.mutation_ai_studio.adapters.in.cli;

import com.mutation.mutation_ai_studio.application.port.in.RemoveSelectedTargetsUseCase;
import com.mutation.mutation_ai_studio.application.port.in.SelectTargetsUseCase;
import com.mutation.mutation_ai_studio.application.port.out.SelectionRepositoryPort;
import com.mutation.mutation_ai_studio.domain.model.JavaClassCandidate;
import com.mutation.mutation_ai_studio.domain.model.SelectionSnapshot;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class SelectCliAdapter implements ApplicationRunner {

    private static final String SELECT_COMMAND = "select";
    private static final String SELECT_ALIAS = "s";

    private final SelectTargetsUseCase selectTargetsUseCase;
    private final RemoveSelectedTargetsUseCase removeSelectedTargetsUseCase;
    private final SelectionRepositoryPort selectionRepositoryPort;

    public SelectCliAdapter(SelectTargetsUseCase selectTargetsUseCase,
                            RemoveSelectedTargetsUseCase removeSelectedTargetsUseCase,
                            SelectionRepositoryPort selectionRepositoryPort) {
        this.selectTargetsUseCase = selectTargetsUseCase;
        this.removeSelectedTargetsUseCase = removeSelectedTargetsUseCase;
        this.selectionRepositoryPort = selectionRepositoryPort;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> commands = args.getNonOptionArgs();
        if (commands.isEmpty() || !isSelectCommand(commands.getFirst())) {
            return;
        }

        String[] sourceArgs = args.getSourceArgs();
        Path projectRoot = resolveProjectRoot(sourceArgs);
        String removeTarget = resolveRemoveTarget(sourceArgs);

        if (removeTarget != null) {
            handleRemove(projectRoot, removeTarget);
            return;
        }

        String classTarget = resolveClassTarget(sourceArgs);
        ScanCategory categoryTarget = resolveCategoryTarget(sourceArgs);

        SelectionSnapshot selection;
        if (classTarget == null && categoryTarget == null) {
            selection = selectTargetsUseCase.selectAll(projectRoot);
        } else if (categoryTarget != null && classTarget == null) {
            selection = selectTargetsUseCase.selectByCategory(projectRoot, categoryTarget.key());
        } else {
            selection = selectTargetsUseCase.selectSingle(projectRoot, classTarget);
        }

        printSummary(projectRoot, selection);
    }

    private void handleRemove(Path projectRoot, String removeTarget) {
        Optional<SelectionSnapshot> updated;
        if (".".equals(removeTarget)) {
            updated = removeSelectedTargetsUseCase.clear(projectRoot);
            if (updated.isEmpty()) {
                System.out.println("Nenhuma seleção existente para limpar.");
                return;
            }

            System.out.printf("Projeto: %s%n", projectRoot);
            System.out.println("Seleção limpa com sucesso.");
            System.out.printf("Seleção salva em: %s%n", selectionRepositoryPort.selectionFilePath(projectRoot));
            return;
        }

        updated = removeSelectedTargetsUseCase.remove(projectRoot, removeTarget);
        if (updated.isEmpty()) {
            System.out.println("Nenhuma seleção existente para remover itens.");
            return;
        }

        System.out.printf("Projeto: %s%n", projectRoot);
        System.out.printf("Remoção aplicada para: %s%n", removeTarget);
        System.out.printf("%d classes selecionadas após remoção%n", updated.get().totalSelected());
        System.out.printf("Seleção salva em: %s%n", selectionRepositoryPort.selectionFilePath(projectRoot));
    }

    private String resolveRemoveTarget(String[] sourceArgs) {
        for (int i = 1; i < sourceArgs.length; i++) {
            String current = sourceArgs[i];

            if (current.startsWith("--remove=")) {
                return current.substring("--remove=".length());
            }

            if ("--remove".equals(current) && i + 1 < sourceArgs.length) {
                return sourceArgs[i + 1];
            }
        }

        return null;
    }

    private boolean isSelectCommand(String command) {
        return SELECT_COMMAND.equals(command) || SELECT_ALIAS.equals(command);
    }

    private Path resolveProjectRoot(String[] sourceArgs) {
        List<String> positionals = extractPositionalArgs(sourceArgs);
        if (positionals.isEmpty()) {
            return Paths.get("").toAbsolutePath().normalize();
        }

        if (positionals.size() == 1) {
            String single = positionals.getFirst();
            if (".".equals(single)) {
                return Paths.get("").toAbsolutePath().normalize();
            }

            Path maybePath = Paths.get(single).toAbsolutePath().normalize();
            if (Files.exists(maybePath) && Files.isDirectory(maybePath)) {
                return maybePath;
            }

            return Paths.get("").toAbsolutePath().normalize();
        }

        String first = positionals.getFirst();
        if (".".equals(first)) {
            return Paths.get("").toAbsolutePath().normalize();
        }

        return Paths.get(first).toAbsolutePath().normalize();
    }

    private String resolveClassTarget(String[] sourceArgs) {
        List<String> positionals = extractPositionalArgs(sourceArgs);
        if (positionals.isEmpty()) {
            return null;
        }

        if (positionals.size() == 1) {
            String single = positionals.getFirst();
            if (".".equals(single)) {
                return null;
            }

            if (ScanCategory.fromToken(single).isPresent()) {
                return null;
            }

            Path maybePath = Paths.get(single).toAbsolutePath().normalize();
            if (Files.exists(maybePath) && Files.isDirectory(maybePath)) {
                return null;
            }

            return single;
        }

        String candidate = positionals.get(1);
        if (ScanCategory.fromToken(candidate).isPresent()) {
            return null;
        }

        return candidate;
    }

    private ScanCategory resolveCategoryTarget(String[] sourceArgs) {
        List<String> positionals = extractPositionalArgs(sourceArgs);
        if (positionals.isEmpty()) {
            return null;
        }

        if (positionals.size() == 1) {
            return ScanCategory.fromToken(positionals.getFirst()).orElse(null);
        }

        return ScanCategory.fromToken(positionals.get(1)).orElse(null);
    }

    private List<String> extractPositionalArgs(String[] sourceArgs) {
        List<String> positional = new ArrayList<>();
        for (int i = 1; i < sourceArgs.length; i++) {
            String current = sourceArgs[i];

            if (current.startsWith("--remove=")) {
                continue;
            }

            if ("--remove".equals(current) && i + 1 < sourceArgs.length) {
                i++;
                continue;
            }

            if (current.startsWith("--")) {
                continue;
            }

            positional.add(current);
        }
        return positional;
    }

    private void printSummary(Path projectRoot, SelectionSnapshot selection) {
        List<JavaClassCandidate> classes = selection.classes();

        int nameWidth = classes.stream()
                .mapToInt(c -> c.className().length())
                .max().orElse(20);

        System.out.println();
        System.out.printf("Projeto: %s%s%s%n", Ansi.BOLD, projectRoot.getFileName(), Ansi.RESET);
        System.out.printf("%d classe(s) selecionada(s)%n%n", selection.totalSelected());

        classes.forEach(c -> System.out.printf(
                "  %-" + nameWidth + "s  %s%s%s%n",
                c.className(),
                Ansi.GRAY, c.packageName(), Ansi.RESET));

        System.out.println();
    }
}
