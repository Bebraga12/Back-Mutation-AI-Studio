package com.mutation.mutation_ai_studio.application.usecase;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.mutation.mutation_ai_studio.domain.model.GeneratedTestCandidate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class GeneratedTestStructuralValidator {

    private static final Set<String> FORBIDDEN_ANNOTATIONS = Set.of(
            "SpringBootTest",
            "WebMvcTest",
            "DataJpaTest",
            "AutoConfigureMockMvc",
            "Autowired"
    );

    public List<String> validate(GeneratedTestCandidate candidate) {
        List<String> errors = new ArrayList<>();
        String sourceCode = candidate.sourceCode() == null ? "" : candidate.sourceCode().trim();
        if (sourceCode.isBlank()) {
            errors.add("Falha estrutural: o modelo nao retornou codigo Java utilizavel.");
            return errors;
        }

        CompilationUnit compilationUnit;
        try {
            compilationUnit = StaticJavaParser.parse(sourceCode);
        } catch (RuntimeException ex) {
            errors.add("Falha estrutural: o teste gerado nao e Java valido.");
            return errors;
        }

        String expectedPackage = candidate.prompt().analysis().packageName();
        String actualPackage = compilationUnit.getPackageDeclaration()
                .map(pkg -> pkg.getNameAsString())
                .orElse("");
        if (!expectedPackage.equals(actualPackage)) {
            errors.add("Falha estrutural: package incorreto. Esperado `" + expectedPackage + "`.");
        }

        ClassOrInterfaceDeclaration primaryClass = compilationUnit.findFirst(ClassOrInterfaceDeclaration.class)
                .orElse(null);
        if (primaryClass == null) {
            errors.add("Falha estrutural: nenhuma classe de teste foi encontrada no codigo gerado.");
            return errors;
        }

        if (!candidate.testClassName().equals(primaryClass.getNameAsString())) {
            errors.add("Falha estrutural: nome da classe incorreto. Esperado `" + candidate.testClassName() + "`.");
        }

        List<String> forbiddenAnnotations = compilationUnit.findAll(com.github.javaparser.ast.expr.AnnotationExpr.class).stream()
                .map(annotation -> annotation.getName().getIdentifier())
                .filter(FORBIDDEN_ANNOTATIONS::contains)
                .distinct()
                .toList();
        if (!forbiddenAnnotations.isEmpty()) {
            errors.add("Falha estrutural: anotacoes proibidas encontradas: "
                    + String.join(", ", forbiddenAnnotations) + ".");
        }

        boolean hasAnyTestMethod = primaryClass.getMethods().stream()
                .anyMatch(method -> method.isAnnotationPresent("Test"));
        if (!hasAnyTestMethod) {
            errors.add("Falha estrutural: o teste gerado nao contem nenhum metodo @Test.");
        }

        List<FieldDeclaration> injectMocksFields = primaryClass.getFields().stream()
                .filter(field -> field.isAnnotationPresent("InjectMocks"))
                .toList();
        if (injectMocksFields.size() != 1) {
            errors.add("Falha estrutural: deve existir exatamente um campo @InjectMocks para a classe alvo.");
        } else {
            String injectMocksType = injectMocksFields.getFirst().getElementType().asString();
            if (!candidate.className().equals(injectMocksType)) {
                errors.add("Falha estrutural: o campo @InjectMocks deve usar a classe alvo `" + candidate.className() + "`.");
            }
        }

        Set<String> publicMethodNames = candidate.prompt().analysis().publicMethods().stream()
                .map(method -> method.methodName())
                .collect(Collectors.toSet());
        List<String> forbiddenSubjectCalls = compilationUnit.findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getScope().isPresent())
                .filter(call -> call.getScope().get() instanceof NameExpr)
                .filter(call -> ((NameExpr) call.getScope().get()).getNameAsString().equals("subject"))
                .map(MethodCallExpr::getNameAsString)
                .filter(name -> !publicMethodNames.contains(name))
                .distinct()
                .toList();
        if (!forbiddenSubjectCalls.isEmpty()) {
            errors.add("Falha estrutural: o teste chama metodos nao publicos ou inexistentes da classe alvo: "
                    + String.join(", ", forbiddenSubjectCalls) + ".");
        }

        return errors;
    }
}
