package com.mutation.mutation_ai_studio.adapters.in.web.project;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

@Service
public class PitestPomPluginInstaller {

    private static final String PITEST_VERSION = "1.17.3";
    private static final String PITEST_JUNIT5_PLUGIN_VERSION = "1.2.3";
    private static final String LEGACY_PITEST_JUNIT5_PLUGIN_VERSION = "1.2.1";

    private static final String PITEST_PLUGIN_BLOCK = """
            <plugin>
                <groupId>org.pitest</groupId>
                <artifactId>pitest-maven</artifactId>
                <version>%s</version>
                <dependencies>
                    <dependency>
                        <groupId>org.pitest</groupId>
                        <artifactId>pitest-junit5-plugin</artifactId>
                        <version>%s</version>
                    </dependency>
                </dependencies>
                <configuration>
                    <outputFormats>
                        <param>XML</param>
                        <param>HTML</param>
                    </outputFormats>
                </configuration>
            </plugin>
            """.formatted(PITEST_VERSION, PITEST_JUNIT5_PLUGIN_VERSION);

    public void ensurePluginInstalled(String repositoryPath) {
        Path pomPath = Path.of(repositoryPath).toAbsolutePath().normalize().resolve("pom.xml");
        if (!Files.exists(pomPath) || !Files.isRegularFile(pomPath)) {
            throw new IllegalArgumentException("Projeto Maven invalido: pom.xml nao encontrado em " + pomPath.getParent());
        }

        try {
            String pomContent = Files.readString(pomPath, StandardCharsets.UTF_8);
            String updatedPomContent = upgradeLegacyJUnit5PluginVersion(pomContent);
            String normalized = updatedPomContent.toLowerCase(Locale.ROOT);
            if (normalized.contains("<artifactid>pitest-maven</artifactid>")) {
                if (!updatedPomContent.equals(pomContent)) {
                    Files.writeString(pomPath, updatedPomContent, StandardCharsets.UTF_8);
                }
                return;
            }

            String updated = injectPlugin(updatedPomContent);
            Files.writeString(pomPath, updated, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Falha ao atualizar pom.xml do projeto: " + exception.getMessage(), exception);
        }
    }

    private String upgradeLegacyJUnit5PluginVersion(String pomContent) {
        String legacyFragment = "<artifactId>pitest-junit5-plugin</artifactId>";
        if (!pomContent.contains(legacyFragment) || !pomContent.contains(LEGACY_PITEST_JUNIT5_PLUGIN_VERSION)) {
            return pomContent;
        }

        return pomContent.replace(
                "<version>" + LEGACY_PITEST_JUNIT5_PLUGIN_VERSION + "</version>",
                "<version>" + PITEST_JUNIT5_PLUGIN_VERSION + "</version>");
    }

    private String injectPlugin(String pomContent) {
        if (pomContent.contains("</plugins>")) {
            return pomContent.replace("</plugins>", indentBlock(PITEST_PLUGIN_BLOCK, detectIndentBeforeClosingTag(pomContent, "</plugins>")) + "\n" + detectIndentBeforeClosingTag(pomContent, "</plugins>") + "</plugins>");
        }

        if (pomContent.contains("</build>")) {
            String indent = detectIndentBeforeClosingTag(pomContent, "</build>");
            String pluginIndent = indent + "    ";
            String pluginsBlock = "\n"
                    + pluginIndent + "<plugins>\n"
                    + indentBlock(PITEST_PLUGIN_BLOCK, pluginIndent + "    ") + "\n"
                    + pluginIndent + "</plugins>\n"
                    + indent;
            return pomContent.replace("</build>", pluginsBlock + "</build>");
        }

        if (pomContent.contains("</project>")) {
            String indent = detectIndentBeforeClosingTag(pomContent, "</project>");
            String buildIndent = indent + "    ";
            String pluginsIndent = buildIndent + "    ";
            String buildBlock = "\n"
                    + buildIndent + "<build>\n"
                    + pluginsIndent + "<plugins>\n"
                    + indentBlock(PITEST_PLUGIN_BLOCK, pluginsIndent + "    ") + "\n"
                    + pluginsIndent + "</plugins>\n"
                    + buildIndent + "</build>\n"
                    + indent;
            return pomContent.replace("</project>", buildBlock + "</project>");
        }

        throw new IllegalArgumentException("pom.xml invalido: tag </project> nao encontrada.");
    }

    private String detectIndentBeforeClosingTag(String content, String closingTag) {
        int closingIndex = content.indexOf(closingTag);
        if (closingIndex <= 0) {
            return "";
        }

        int lineStart = content.lastIndexOf('\n', closingIndex);
        if (lineStart < 0) {
            return "";
        }

        StringBuilder indent = new StringBuilder();
        for (int index = lineStart + 1; index < closingIndex; index++) {
            char current = content.charAt(index);
            if (current == ' ' || current == '\t') {
                indent.append(current);
                continue;
            }
            break;
        }
        return indent.toString();
    }

    private String indentBlock(String block, String indent) {
        String[] lines = block.strip().split("\n");
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) {
                builder.append('\n');
            }
            builder.append(indent).append(lines[index]);
        }
        return builder.toString();
    }
}
