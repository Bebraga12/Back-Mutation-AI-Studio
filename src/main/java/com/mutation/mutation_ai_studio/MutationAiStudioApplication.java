package com.mutation.mutation_ai_studio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;

@SpringBootApplication
public class MutationAiStudioApplication {

	public static void main(String[] args) {
		System.setProperty("java.awt.headless", "false");
		SpringApplication application = new SpringApplication(MutationAiStudioApplication.class);
		boolean cliScanMode = isCliScanMode(args);

		if (cliScanMode) {
			application.setWebApplicationType(WebApplicationType.NONE);
			application.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
			application.setDefaultProperties(Map.of(
					"spring.autoconfigure.exclude",
					"org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
							+ "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
							+ "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
					"logging.level.root", "ERROR",
					"spring.main.log-startup-info", "false"
			));
		}

		ConfigurableApplicationContext context = application.run(args);

		if (cliScanMode) {
			int exitCode = SpringApplication.exit(context);
			System.exit(exitCode);
		}
	}

	private static boolean isCliScanMode(String[] args) {
		if (args == null || args.length == 0) {
			return false;
		}

		String command = args[0];
		return "scan".equals(command)
				|| "select".equals(command)
				|| "s".equals(command)
				|| "status".equals(command)
				|| "create".equals(command)
				|| "c".equals(command);
	}

}
