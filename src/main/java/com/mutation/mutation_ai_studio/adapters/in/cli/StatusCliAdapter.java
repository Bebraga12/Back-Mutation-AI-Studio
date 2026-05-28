package com.mutation.mutation_ai_studio.adapters.in.cli;

import com.mutation.mutation_ai_studio.application.port.in.ReadSelectionStatusUseCase;
import com.mutation.mutation_ai_studio.domain.model.SelectionSnapshot;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

@Component
public class StatusCliAdapter implements ApplicationRunner {

    private static final String STATUS_COMMAND = "status";

    private final ReadSelectionStatusUseCase readSelectionStatusUseCase;

    public StatusCliAdapter(ReadSelectionStatusUseCase readSelectionStatusUseCase) {
        this.readSelectionStatusUseCase = readSelectionStatusUseCase;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> commands = args.getNonOptionArgs();
        if (commands.isEmpty() || !STATUS_COMMAND.equals(commands.getFirst())) {
            return;
        }

        Path projectRoot = resolveProjectRoot(commands);
        Optional<SelectionSnapshot> maybeSelection = readSelectionStatusUseCase.read(projectRoot);

        System.out.println();
        System.out.printf("Projeto: %s%s%s%n%n", Ansi.BOLD, projectRoot.getFileName(), Ansi.RESET);

        if (maybeSelection.isEmpty()) {
            System.out.printf("  %sNenhuma seleção encontrada.%s%n", Ansi.YELLOW, Ansi.RESET);
            System.out.println("  Use 'mutation-ai select .' ou 'mutation-ai c t --pick' para selecionar classes.");
            System.out.println();
            return;
        }

        SelectionSnapshot selection = maybeSelection.get();
        int nameWidth = selection.classes().stream()
                .mapToInt(c -> c.className().length())
                .max().orElse(20);

        System.out.printf("  %d classe(s) selecionada(s)%n%n", selection.totalSelected());

        selection.classes().forEach(c -> System.out.printf(
                "  %-" + nameWidth + "s  %s%s%s%n",
                c.className(),
                Ansi.GRAY, c.packageName(), Ansi.RESET));

        System.out.println();
    }

    private Path resolveProjectRoot(List<String> commands) {
        if (commands.size() < 2 || ".".equals(commands.get(1))) {
            return Paths.get("").toAbsolutePath().normalize();
        }

        return Paths.get(commands.get(1)).toAbsolutePath().normalize();
    }
}
