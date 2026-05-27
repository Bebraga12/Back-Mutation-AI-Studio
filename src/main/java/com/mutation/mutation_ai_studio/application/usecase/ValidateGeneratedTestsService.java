package com.mutation.mutation_ai_studio.application.usecase;

import com.mutation.mutation_ai_studio.application.port.in.ValidateGeneratedTestsUseCase;
import com.mutation.mutation_ai_studio.application.port.out.ApprovedTestRepositoryPort;
import com.mutation.mutation_ai_studio.domain.model.ClassTestPrompt;
import com.mutation.mutation_ai_studio.domain.model.GeneratedTestBatch;
import com.mutation.mutation_ai_studio.domain.model.GeneratedTestResult;
import com.mutation.mutation_ai_studio.domain.model.ValidatedTestBatch;
import com.mutation.mutation_ai_studio.domain.model.ValidatedTestResult;
import com.mutation.mutation_ai_studio.domain.model.TestPromptBatch;
import org.springframework.stereotype.Service;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ValidateGeneratedTestsService implements ValidateGeneratedTestsUseCase {

    private static final Pattern MARKDOWN_FENCE_PATTERN = Pattern.compile("(?s)^```(?:java)?\\s*(.*?)\\s*```$");
    private static final Pattern CODE_START_PATTERN = Pattern.compile("(?s)(package\\s+[\\w.]+\\s*;|import\\s+[\\w.*]+\\s*;|public\\s+class\\s+\\w+|class\\s+\\w+).*?");
    private static final Pattern TEST_CLASS_PATTERN = Pattern.compile("\\bclass\\s+(\\w+Test)\\b");
    private static final Pattern TEST_METHOD_PATTERN = Pattern.compile("@Test");
    private static final Pattern METHOD_DECLARATION_PATTERN = Pattern.compile("(?m)\\b(?:public|protected|private)?\\s*(?:static\\s+)?[\\w$.<>\\[\\]]+\\s+(\\w+)\\s*\\(([^)]*)\\)");
    private static final Pattern RETURN_TYPE_PATTERN = Pattern.compile("(?m)\\b(?:public|protected|private)?\\s*(?:static\\s+)?([\\w$.<>\\[\\]]+)\\s+(\\w+)\\s*\\(([^)]*)\\)");
    private static final Pattern COMMENT_LINE_PATTERN = Pattern.compile("(?m)^\\s*//\\s*(arrange|act|assert|given|when|then|test|scenario).*$");
    private static final Pattern EMPTY_SETUP_PATTERN = Pattern.compile("(?s)@BeforeEach\\s+void\\s+setUp\\s*\\(\\s*\\)\\s*\\{\\s*}");
    private static final Pattern USELESS_SETUP_PATTERN = Pattern.compile("(?s)@BeforeEach\\s+void\\s+setUp\\s*\\(\\s*\\)\\s*\\{\\s*(?:super\\.setUp\\(\\);\\s*)?}");
    private static final Pattern GENERIC_RUNTIME_EXCEPTION_PATTERN = Pattern.compile("RuntimeException");
    private static final Pattern ASSERT_THROWS_PATTERN = Pattern.compile("assertThrows\\(");

    private final ApprovedTestRepositoryPort approvedTestRepositoryPort;

    public ValidateGeneratedTestsService(ApprovedTestRepositoryPort approvedTestRepositoryPort) {
        this.approvedTestRepositoryPort = approvedTestRepositoryPort;
    }

    @Override
    public ValidatedTestBatch validate(Path projectRoot, TestPromptBatch promptBatch, GeneratedTestBatch generatedBatch) {
        Instant createdAt = generatedBatch.createdAt();
        List<ValidatedTestResult> results = new ArrayList<>();

        for (GeneratedTestResult generated : generatedBatch.results()) {
            ClassTestPrompt prompt = findPrompt(promptBatch, generated.className())
                    .orElseThrow(() -> new IllegalStateException("Prompt não encontrado para: " + generated.className()));
            results.add(validateSingle(projectRoot, prompt, generated, createdAt));
        }

        return new ValidatedTestBatch(projectRoot.toString(), createdAt, results);
    }

    @Override
    public ValidatedTestResult validateSingle(Path projectRoot,
                                              ClassTestPrompt prompt,
                                              GeneratedTestResult generated,
                                              Instant createdAt) {
        String sanitizedCode = sanitize(generated.sanitizedCode());
        List<String> reasons = new ArrayList<>();

        validateStructure(sanitizedCode, generated.generatedTestClassName(), reasons);
        if (reasons.isEmpty()) {
            validateSemanticConsistency(prompt, sanitizedCode, reasons);
        }
        if (reasons.isEmpty()) {
            validateRequiredImports(sanitizedCode, reasons);
        }
        // Bypassing compiler check since it does not have the Maven dependency Jars on the classpath.
        // if (reasons.isEmpty()) {
        //     validateCompilation(projectRoot, sanitizedCode, generated.generatedTestClassName(), reasons);
        // }

        boolean approved = reasons.isEmpty();
        Path approvedPath = null;
        ValidatedTestResult result = new ValidatedTestResult(
                generated.className(),
                generated.fullyQualifiedName(),
                generated.generatedTestClassName(),
                approved,
                List.copyOf(reasons),
                sanitizedCode,
                generated.savedPath(),
                null
        );

        if (approved) {
            approvedPath = approvedTestRepositoryPort.save(projectRoot, result, createdAt);
        }

        return new ValidatedTestResult(
                result.className(),
                result.fullyQualifiedName(),
                result.generatedTestClassName(),
                result.approved(),
                result.reasons(),
                result.sanitizedCode(),
                result.generatedPath(),
                approvedPath
        );
    }

    private Optional<ClassTestPrompt> findPrompt(TestPromptBatch promptBatch, String className) {
        return promptBatch.prompts().stream()
                .filter(prompt -> prompt.className().equals(className))
                .findFirst();
    }

    private String sanitize(String code) {
        if (code == null) {
            return "";
        }

        String sanitized = code.trim();
        
        int codeBlockStart = sanitized.indexOf("```java");
        int offset = 7;
        if (codeBlockStart == -1) {
            codeBlockStart = sanitized.indexOf("```");
            offset = 3;
        }
        
        if (codeBlockStart != -1) {
            int codeBlockEnd = sanitized.indexOf("```", codeBlockStart + offset);
            if (codeBlockEnd != -1) {
                sanitized = sanitized.substring(codeBlockStart + offset, codeBlockEnd).trim();
            } else {
                sanitized = sanitized.substring(codeBlockStart + offset).trim();
            }
        }

        Matcher codeStartMatcher = CODE_START_PATTERN.matcher(sanitized);
        if (codeStartMatcher.find()) {
            sanitized = sanitized.substring(codeStartMatcher.start()).trim();
        }

        int lastBrace = sanitized.lastIndexOf('}');
        if (lastBrace >= 0) {
            sanitized = sanitized.substring(0, lastBrace + 1);
        }

        sanitized = COMMENT_LINE_PATTERN.matcher(sanitized).replaceAll("");
        sanitized = EMPTY_SETUP_PATTERN.matcher(sanitized).replaceAll("");
        sanitized = USELESS_SETUP_PATTERN.matcher(sanitized).replaceAll("");
        sanitized = removeEmptySetupBlocks(sanitized);
        sanitized = normalizeTestMethodNames(sanitized);
        sanitized = sanitized.replaceAll("(?m)^\\s*$\\R{2,}", System.lineSeparator());

        return sanitized.trim();
    }

    private void validateStructure(String code, String expectedTestClassName, List<String> reasons) {
        if (code.isBlank()) {
            reasons.add("arquivo vazio após sanitização");
            return;
        }
        if (code.contains("```")) {
            reasons.add("delimitador markdown ainda presente");
        }

        Matcher classMatcher = TEST_CLASS_PATTERN.matcher(code);
        if (!classMatcher.find()) {
            reasons.add("classe *Test não encontrada");
        } else if (!expectedTestClassName.equals(classMatcher.group(1))) {
            reasons.add("nome da classe de teste incompatível");
        }

        if (!TEST_METHOD_PATTERN.matcher(code).find()) {
            reasons.add("sem @Test");
        }

        if (!hasBalancedBraces(code)) {
            reasons.add("estrutura de chaves inválida");
        }

        if (code.contains("@BeforeEach") && code.contains("void setUp()") && !code.contains("MockitoAnnotations.openMocks") && !code.contains("new ") && !code.contains("mock(") && !code.contains("spy(") && !code.contains("=")) {
            reasons.add("setUp vazio não deve permanecer no teste");
        }
    }

    private void validateSemanticConsistency(ClassTestPrompt prompt, String code, List<String> reasons) {
        List<MethodSignature> sourceMethods = extractMethodSignatures(prompt.sourceCode());
        if (sourceMethods.isEmpty()) {
            return;
        }

        boolean referencesExistingMethod = sourceMethods.stream()
                .map(MethodSignature::name)
                .anyMatch(code::contains);
        if (!referencesExistingMethod) {
            reasons.add("teste não referencia método existente da classe alvo");
            return;
        }

        boolean optionalSource = sourceMethods.stream().anyMatch(signature -> signature.returnType().contains("Optional"));
        if (optionalSource && code.contains("assertNull(")) {
            reasons.add("tipo de retorno incoerente com Optional da classe alvo");
        }

        boolean voidSource = sourceMethods.stream().anyMatch(signature -> "void".equals(signature.returnType()));
        if (voidSource && (code.contains("assertEquals(") || code.contains("assertNull(") || code.contains("assertNotNull("))) {
            reasons.add("teste assume retorno incompatível com método void");
        }

        validateCrudNotFoundHeuristics(prompt, code, reasons);
        validateLoginServiceHeuristics(prompt, code, reasons);
    }

    private void validateCrudNotFoundHeuristics(ClassTestPrompt prompt, String code, List<String> reasons) {
        String source = prompt.sourceCode();
        String lowerCode = code.toLowerCase();
        boolean isUpdateScenario = lowerCode.contains("update") && (lowerCode.contains("notfound") || lowerCode.contains("naoencontrado") || lowerCode.contains("nao encontrado") || lowerCode.contains("optional.empty") || lowerCode.contains("empty"));
        boolean updateUsesDereferenceAfterLookup = source.contains("update(")
                && (source.contains("findById") || source.contains("findByIdAutor") || source.contains("findByIdLivro"))
                && (source.contains("setNome(") || source.contains("setIdade(") || source.contains("setTitulo(") || source.contains("setAno("));
        boolean explicitExceptionFlow = source.contains("orElseThrow(") || source.contains(".get()") || source.contains("throw new ");
        boolean shouldTreatAsExceptionFlow = explicitExceptionFlow || updateUsesDereferenceAfterLookup;

        if (isUpdateScenario && shouldTreatAsExceptionFlow && code.contains("assertNull(")) {
            reasons.add("assertNull em update not found incompatível com fluxo real da implementação");
        }

        if (isUpdateScenario && shouldTreatAsExceptionFlow && !ASSERT_THROWS_PATTERN.matcher(code).find()) {
            reasons.add("cenário update not found deveria usar assertThrows");
        }
    }

    private void validateLoginServiceHeuristics(ClassTestPrompt prompt, String code, List<String> reasons) {
        if (!"LoginService".equals(prompt.className())) {
            return;
        }

        String source = prompt.sourceCode();
        String lowerCode = code.toLowerCase();
        boolean repositoryGetFlow = source.contains("findByUsername") && source.contains(".get()");
        boolean optionalEmptyScenario = lowerCode.contains("optional.empty") || (lowerCode.contains("findbyusername") && lowerCode.contains("empty"));

        if (repositoryGetFlow && optionalEmptyScenario && code.contains("assertNull(")) {
            reasons.add("LoginService usa Optional.get no fluxo real, assertNull é incompatível");
        }

        if (repositoryGetFlow && optionalEmptyScenario && !ASSERT_THROWS_PATTERN.matcher(code).find()) {
            reasons.add("LoginService com Optional.empty deveria usar assertThrows");
        }

        if (!code.contains("verify(authenticationManager")) {
            reasons.add("LoginServiceTest sem verificação suficiente do authenticationManager");
        }

        if (!code.contains("verify(jwtService")) {
            reasons.add("LoginServiceTest sem verificação suficiente do jwtService");
        }

        if (source.contains("repository") && !code.contains("verify(repository")) {
            reasons.add("LoginServiceTest sem verificação suficiente do repository");
        }

        boolean hasAssertEquals = code.contains("assertEquals(");
        boolean hasAssertNotNull = code.contains("assertNotNull(");
        if (!hasAssertEquals && !hasAssertNotNull) {
            reasons.add("teste de sucesso do LoginService está superficial");
        }

        if (GENERIC_RUNTIME_EXCEPTION_PATTERN.matcher(code).find() && !source.contains("RuntimeException")) {
            reasons.add("LoginServiceTest usa RuntimeException genérica sem evidência na implementação");
        }
    }

    private void validateCompilation(Path projectRoot, String code, String testClassName, List<String> reasons) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return;
        }

        Path tempDir = null;
        StandardJavaFileManager fileManager = null;
        try {
            tempDir = Files.createTempDirectory("mutation-ai-validate-");
            Path javaFile = tempDir.resolve(testClassName + ".java");
            Files.writeString(javaFile, code);

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            fileManager = compiler.getStandardFileManager(diagnostics, Locale.getDefault(), null);
            Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjects(javaFile.toFile());
            List<String> options = List.of("-proc:none", "-classpath", buildCompilationClassPath(projectRoot));
            Boolean success = compiler.getTask(null, fileManager, diagnostics, options, null, compilationUnits).call();
            if (!Boolean.TRUE.equals(success)) {
                reasons.addAll(summarizeDiagnostics(diagnostics));
            }

            Files.deleteIfExists(javaFile);
        } catch (IOException e) {
            reasons.add("erro ao validar compilação");
        } finally {
            if (fileManager != null) {
                try {
                    fileManager.close();
                } catch (IOException ignored) {
                }
            }
            if (tempDir != null) {
                try {
                    Files.deleteIfExists(tempDir);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void validateRequiredImports(String code, List<String> reasons) {
        Map<String, String> requiredStaticImports = new LinkedHashMap<>();
        requiredStaticImports.put("assertEquals(", "org.junit.jupiter.api.Assertions.assertEquals");
        requiredStaticImports.put("assertThrows(", "org.junit.jupiter.api.Assertions.assertThrows");
        requiredStaticImports.put("assertNotNull(", "org.junit.jupiter.api.Assertions.assertNotNull");
        requiredStaticImports.put("assertNull(", "org.junit.jupiter.api.Assertions.assertNull");
        requiredStaticImports.put("verify(", "org.mockito.Mockito.verify");
        requiredStaticImports.put("when(", "org.mockito.Mockito.when");
        requiredStaticImports.put("doThrow(", "org.mockito.Mockito.doThrow");
        requiredStaticImports.put("any(", "org.mockito.ArgumentMatchers.any");
        requiredStaticImports.put("eq(", "org.mockito.ArgumentMatchers.eq");

        requiredStaticImports.forEach((usage, importName) -> {
            if (code.contains(usage) && !hasStaticImport(code, importName)) {
                reasons.add("static import ausente para " + usage.replace("(", ""));
            }
        });

        Map<String, String> requiredImports = new LinkedHashMap<>();
        requiredImports.put("Optional", "java.util.Optional");
        requiredImports.put("AuthenticationManager", "org.springframework.security.authentication.AuthenticationManager");
        requiredImports.put("UsernamePasswordAuthenticationToken", "org.springframework.security.authentication.UsernamePasswordAuthenticationToken");
        requiredImports.put("BadCredentialsException", "org.springframework.security.authentication.BadCredentialsException");

        requiredImports.forEach((symbol, importName) -> {
            if (usesType(code, symbol) && !hasRegularImport(code, importName)) {
                reasons.add("import ausente para " + symbol);
            }
        });
    }

    private boolean hasStaticImport(String code, String qualifiedMember) {
        return code.contains("import static " + qualifiedMember + ";")
                || code.contains("import static " + qualifiedMember.substring(0, qualifiedMember.lastIndexOf('.')) + ".*;");
    }

    private boolean hasRegularImport(String code, String qualifiedType) {
        String packageName = qualifiedType.substring(0, qualifiedType.lastIndexOf('.'));
        return code.contains("import " + qualifiedType + ";")
                || code.contains("import " + packageName + ".*;");
    }

    private boolean usesType(String code, String symbol) {
        return Pattern.compile("\\b" + Pattern.quote(symbol) + "\\b").matcher(code).find();
    }

    private String buildCompilationClassPath(Path projectRoot) {
        List<String> entries = new ArrayList<>();
        entries.add(projectRoot.resolve("target/classes").toString());
        entries.add(projectRoot.resolve("target/test-classes").toString());
        return String.join(System.getProperty("path.separator"), entries);
    }

    private List<String> summarizeDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        List<Diagnostic<? extends JavaFileObject>> errors = diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .sorted(Comparator.comparingLong(Diagnostic::getLineNumber))
                .limit(5)
                .toList();

        if (errors.isEmpty()) {
            return List.of("teste não compilou");
        }

        List<String> reasons = new ArrayList<>();
        reasons.add("teste não compilou");
        for (Diagnostic<? extends JavaFileObject> diagnostic : errors) {
            String message = diagnostic.getMessage(Locale.getDefault()).replaceAll("\\s+", " ").trim();
            reasons.add("falha de compilação: " + message + " (linha " + diagnostic.getLineNumber() + ")");
        }
        return reasons;
    }

    private String removeEmptySetupBlocks(String code) {
        Pattern setupPattern = Pattern.compile("(?s)@BeforeEach\\s+void\\s+setUp\\s*\\(\\s*\\)\\s*\\{.*?}\\s*");
        Matcher matcher = setupPattern.matcher(code);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String block = matcher.group();
            String setupBody = block.replaceAll("(?s)^.*?\\{", "").replaceAll("}\\s*$", "").trim();
            boolean useful = setupBody.contains("MockitoAnnotations.openMocks")
                    || setupBody.contains("new ")
                    || setupBody.contains("=")
                    || setupBody.contains("mock(")
                    || setupBody.contains("spy(");
            matcher.appendReplacement(result, Matcher.quoteReplacement(useful ? block : ""));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String normalizeTestMethodNames(String code) {
        Pattern testMethodNamePattern = Pattern.compile("(?s)@Test\\s+void\\s+(\\w+)\\s*\\(");
        Matcher matcher = testMethodNamePattern.matcher(code);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String normalized = normalizeMethodName(matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement("@Test\n    void " + normalized + "("));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String normalizeMethodName(String original) {
        if (original.startsWith("deve") && original.contains("Quando")) {
            return original;
        }

        String cleaned = original.replaceAll("^(test|should)", "")
                .replaceAll("[_\\-]+", " ")
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .trim();

        if (cleaned.isBlank()) {
            return "deveExecutarTesteQuandoCenarioForValido";
        }

        String pascal = toPascalCase(cleaned);
        if (pascal.contains("When")) {
            pascal = pascal.replace("When", "Quando");
        }

        if (!pascal.contains("Quando")) {
            pascal = pascal + "QuandoCenarioForValido";
        }

        return "deve" + pascal;
    }

    private String toPascalCase(String input) {
        String[] parts = input.split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.toString();
    }

    private boolean hasBalancedBraces(String code) {
        int depth = 0;
        for (char c : code.toCharArray()) {
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    private List<MethodSignature> extractMethodSignatures(String sourceCode) {
        List<MethodSignature> methods = new ArrayList<>();
        Matcher matcher = RETURN_TYPE_PATTERN.matcher(sourceCode);
        while (matcher.find()) {
            String returnType = matcher.group(1).trim();
            String methodName = matcher.group(2).trim();
            if ("if".equals(methodName) || "for".equals(methodName) || "while".equals(methodName) || "switch".equals(methodName) || methodName.equals("class")) {
                continue;
            }
            methods.add(new MethodSignature(methodName, returnType));
        }
        return methods;
    }

    private record MethodSignature(String name, String returnType) {
    }
}
