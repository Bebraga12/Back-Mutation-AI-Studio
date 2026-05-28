package com.mutation.mutation_ai_studio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class MutationAiStudioApplication {

	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(MutationAiStudioApplication.class);

		if (isCliMode(args)) {
			application.setWebApplicationType(WebApplicationType.NONE);
			ConfigurableApplicationContext context = application.run(args);
			int exitCode = SpringApplication.exit(context);
			System.exit(exitCode);
		} else {
			application.run(args);
		}
	}

	private static boolean isCliMode(String[] args) {
		if (args == null || args.length == 0) {
			return false;
		}
		return switch (args[0]) {
			case "scan", "select", "s", "status", "create", "c", "help", "--help", "-h" -> true;
			default -> false;
		};
	}
}
