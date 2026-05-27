package com.mutation.mutation_ai_studio.adapters.out.filesystem;

import com.mutation.mutation_ai_studio.application.port.out.ProjectClassScannerPort;
import com.mutation.mutation_ai_studio.domain.model.JavaClassCandidate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
public class FileSystemProjectClassScannerAdapter implements ProjectClassScannerPort {

    private static final Path MAIN_JAVA_PATH = Paths.get("src", "main", "java");

    /**
     * Detecta declarações de interface ou annotation type (@interface).
     * Exemplos: "public interface Foo", "public @interface Bar", "interface Baz"
     */
    private static final Pattern INTERFACE_OR_ANNOTATION_TYPE = Pattern.compile(
            "(?m)^\\s*(?:public\\s+)?(?:abstract\\s+|sealed\\s+|non-sealed\\s+)*@?interface\\s+\\w"
    );

    /**
     * Detecta declarações de enum.
     * Exemplo: "public enum Status"
     */
    private static final Pattern ENUM_DECLARATION = Pattern.compile(
            "(?m)^\\s*(?:public\\s+)?enum\\s+\\w"
    );

    /**
     * Detecta declarações de método (não-campo). Exemplos:
     *   public String getFoo()
     *   private boolean isValid(String s)
     *   @Override public List<Autor> findAll()
     * NÃO casa com declarações de campo (sem parênteses) nem com "public class Foo {".
     */
    private static final Pattern HAS_DECLARED_METHOD = Pattern.compile(
            "(?m)^[ \\t]*(?:@\\w+(?:\\([^)\\n]*\\))?[ \\t]*)?(?:public|private|protected)[ \\t]+" +
            "(?:(?:static|final|synchronized|abstract|native)[ \\t]+)*" +
            "(?!class[ \\t]|interface[ \\t]|enum[ \\t])[\\w$<>\\[\\]]+[^(;\\n]*\\("
    );

    @Override
    public List<JavaClassCandidate> findClasses(Path projectRoot) {
        Path scanRoot = projectRoot.resolve(MAIN_JAVA_PATH).normalize();

        if (!Files.exists(scanRoot) || !Files.isDirectory(scanRoot)) {
            return List.of();
        }

        try (Stream<Path> paths = Files.walk(scanRoot)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(this::isJavaSource)
                    .filter(this::isUnitTestable)
                    .map(path -> toCandidate(scanRoot, path))
                    .sorted(Comparator.comparing(JavaClassCandidate::fullyQualifiedName))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Erro ao escanear classes em: " + scanRoot, e);
        }
    }

    private boolean isJavaSource(Path path) {
        String fileName = path.getFileName().toString();
        if (!fileName.endsWith(".java")) {
            return false;
        }
        if (fileName.endsWith("Test.java")) {
            return false;
        }
        return !fileName.equals("package-info.java") && !fileName.equals("module-info.java");
    }

    /**
     * Retorna true apenas para classes que fazem sentido como alvo de unit tests com Mockito.
     * Exclui:
     * - interfaces e annotation types (@interface) — não são instanciáveis
     * - enums — não têm lógica de negócio injetável
     * - entidades JPA (@Entity) — apenas dados, sem lógica de serviço
     * - classe principal Spring Boot (@SpringBootApplication) — só ponto de entrada
     * - Spring Data repositories (extends JpaRepository etc.) — contrato JPA, sem lógica
     */
    private boolean isUnitTestable(Path sourcePath) {
        String content;
        try {
            content = Files.readString(sourcePath);
        } catch (IOException e) {
            return true; // não conseguiu ler — inclui para não esconder
        }

        // Interfaces e annotation types
        if (INTERFACE_OR_ANNOTATION_TYPE.matcher(content).find()) {
            return false;
        }

        // Enums
        if (ENUM_DECLARATION.matcher(content).find()) {
            return false;
        }

        // Entidades JPA — só dados, sem lógica testável
        if (content.contains("@Entity")) {
            return false;
        }

        // Classe principal da aplicação Spring Boot
        if (content.contains("@SpringBootApplication")) {
            return false;
        }

        // Spring Data repositories — interfaces geradas pelo framework
        if (content.contains("extends JpaRepository")
                || content.contains("extends CrudRepository")
                || content.contains("extends PagingAndSortingRepository")
                || content.contains("extends MongoRepository")
                || content.contains("extends ReactiveCrudRepository")
                || content.contains("extends ReactiveMongoRepository")) {
            return false;
        }

        // DTO/VO puro — tem anotações Lombok de dados mas nenhum estereótipo Spring
        // e nenhum método declarado explicitamente na fonte. O Lombok gera getters/setters
        // em tempo de compilação; não há lógica de negócio para testar com Mockito.
        if (isPureLombokDataClass(content)) {
            return false;
        }

        // Configuração Spring Security — @EnableWebSecurity marca classes que constroem
        // a cadeia de filtros via HttpSecurity DSL; esses builders usam generics complexos
        // e acesso a contexto Spring que não podem ser mockados com Mockito padrão.
        if (content.contains("@EnableWebSecurity")) {
            return false;
        }

        return true;
    }

    /**
     * Retorna true para classes que são apenas contêineres de dados Lombok sem lógica
     * de negócio: têm anotações como @Getter/@Setter/@Data mas nenhum estereótipo Spring
     * e nenhum método declarado no código-fonte.
     */
    private boolean isPureLombokDataClass(String content) {
        boolean hasLombokDataAnnotation = content.contains("@Getter")
                || content.contains("@Setter")
                || content.contains("@Data");
        if (!hasLombokDataAnnotation) {
            return false;
        }

        // Se tiver estereótipo Spring, é um componente gerenciado — inclui para teste
        boolean hasSpringStereotype = content.contains("@Service")
                || content.contains("@Component")
                || content.contains("@Controller")
                || content.contains("@RestController")
                || content.contains("@Configuration")
                || content.contains("@Repository");
        if (hasSpringStereotype) {
            return false;
        }

        // Se tiver métodos declarados explicitamente, tem lógica que vale testar
        return !HAS_DECLARED_METHOD.matcher(content).find();
    }

    private JavaClassCandidate toCandidate(Path scanRoot, Path absoluteClassPath) {
        Path relative = scanRoot.relativize(absoluteClassPath);

        String relativePath = relative.toString().replace('\\', '/');
        String className = removeJavaSuffix(absoluteClassPath.getFileName().toString());

        String packageName = relative.getParent() == null
                ? ""
                : relative.getParent().toString()
                .replace('/', '.')
                .replace('\\', '.');

        String fullyQualifiedName = packageName.isBlank()
                ? className
                : packageName + "." + className;

        return new JavaClassCandidate(className, packageName, fullyQualifiedName, relativePath);
    }

    private String removeJavaSuffix(String fileName) {
        return fileName.substring(0, fileName.length() - ".java".length());
    }
}
