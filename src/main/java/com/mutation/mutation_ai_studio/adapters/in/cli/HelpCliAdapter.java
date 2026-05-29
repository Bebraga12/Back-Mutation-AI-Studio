package com.mutation.mutation_ai_studio.adapters.in.cli;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

@Component
@Order(0)
public class HelpCliAdapter implements ApplicationRunner {

    private final ApplicationContext applicationContext;

    public HelpCliAdapter(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (applicationContext instanceof WebApplicationContext) {
            return;
        }

        String[] sourceArgs = args.getSourceArgs();
        boolean noArgs = sourceArgs.length == 0;
        boolean helpFlag = sourceArgs.length > 0
                && (sourceArgs[0].equals("help")
                || sourceArgs[0].equals("--help")
                || sourceArgs[0].equals("-h"));

        if (!noArgs && !helpFlag) {
            return;
        }

        System.out.println();
        System.out.println(Ansi.BOLD + "mutation-ai" + Ansi.RESET + " — gerador de testes unitários com IA local (Ollama)");
        System.out.println();
        System.out.println(Ansi.BOLD + "Uso:" + Ansi.RESET);
        System.out.printf("  %smutation-ai scan%s [.]           Escaneia o projeto e lista classes%n", Ansi.CYAN, Ansi.RESET);
        System.out.printf("  %smutation-ai select%s [.]          Seleciona todas as classes testáveis%n", Ansi.CYAN, Ansi.RESET);
        System.out.printf("  %smutation-ai status%s [.]          Mostra a seleção atual%n", Ansi.CYAN, Ansi.RESET);
        System.out.printf("  %smutation-ai c t%s [.] [--pick]    Gera testes para as classes selecionadas%n", Ansi.CYAN, Ansi.RESET);
        System.out.println();
        System.out.println(Ansi.BOLD + "Flags do scan:" + Ansi.RESET);
        System.out.println("  --verbose              Lista todas as classes com FQCN e path");
        System.out.println("  --focus testable       Foca nas classes mais relevantes para testes");
        System.out.println("  <categoria>            service | controller | repository | entity |");
        System.out.println("                         dto | config | security | core | other");
        System.out.println();
        System.out.println(Ansi.BOLD + "Flags do c t:" + Ansi.RESET);
        System.out.println("  --pick                 Escolhe interativamente as classes antes de gerar");
        System.out.println();
        System.out.println(Ansi.BOLD + "Variáveis de ambiente:" + Ansi.RESET);
        System.out.println("  MUTATION_AI_OLLAMA_BASE_URL   URL do Ollama  (padrão: http://localhost:11434)");
        System.out.println("  MUTATION_AI_OLLAMA_MODEL      Modelo         (padrão: qwen2.5-coder:7b)");
        System.out.println();
    }
}
