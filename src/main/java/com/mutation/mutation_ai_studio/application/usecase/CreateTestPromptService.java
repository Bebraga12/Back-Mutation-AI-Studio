package com.mutation.mutation_ai_studio.application.usecase;

import com.mutation.mutation_ai_studio.application.port.in.CreateTestPromptUseCase;
import com.mutation.mutation_ai_studio.application.port.out.SelectionRepositoryPort;
import com.mutation.mutation_ai_studio.application.port.out.SourceCodeAnalyzerPort;
import com.mutation.mutation_ai_studio.domain.model.ClassAnalysis;
import com.mutation.mutation_ai_studio.domain.model.ClassTestPrompt;
import com.mutation.mutation_ai_studio.domain.model.JavaClassCandidate;
import com.mutation.mutation_ai_studio.domain.model.MethodAnalysis;
import com.mutation.mutation_ai_studio.domain.model.SelectionSnapshot;
import com.mutation.mutation_ai_studio.domain.model.TestPromptBatch;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class CreateTestPromptService implements CreateTestPromptUseCase {

    private final SelectionRepositoryPort selectionRepositoryPort;
    private final SourceCodeAnalyzerPort sourceCodeAnalyzerPort;

    public CreateTestPromptService(SelectionRepositoryPort selectionRepositoryPort,
                                   SourceCodeAnalyzerPort sourceCodeAnalyzerPort) {
        this.selectionRepositoryPort = selectionRepositoryPort;
        this.sourceCodeAnalyzerPort = sourceCodeAnalyzerPort;
    }

    @Override
    public TestPromptBatch create(Path projectRoot) {
        SelectionSnapshot selection = selectionRepositoryPort.read(projectRoot)
                .orElseThrow(() -> new IllegalStateException("Nenhuma seleção encontrada para o projeto. Use `mutation-ai select .` antes de criar o prompt."));

        List<ClassTestPrompt> prompts = selection.classes().stream()
                .map(candidate -> toPrompt(projectRoot, candidate))
                .toList();

        return new TestPromptBatch(
                projectRoot.toString(),
                Instant.now(),
                selection.totalSelected(),
                prompts
        );
    }

    private ClassTestPrompt toPrompt(Path projectRoot, JavaClassCandidate candidate) {
        Path sourceFile = projectRoot.resolve("src/main/java").resolve(candidate.relativePath()).normalize();
        String rawSourceCode = readSourceCode(sourceFile);
        // JavaParser recebe o source original — strings com "://" não são confundidas com comentários
        ClassAnalysis analysis = sourceCodeAnalyzerPort.analyze(projectRoot, candidate, rawSourceCode);
        // O AI recebe a versão sanitizada (sem comentários) para economizar tokens
        String sourceCode = sanitizeSourceCode(rawSourceCode);
        List<String> dependencies = combineDependencies(analysis);
        String prompt = buildPrompt(candidate, sourceCode, dependencies, analysis);

        return new ClassTestPrompt(
                candidate.className(),
                candidate.fullyQualifiedName(),
                candidate.relativePath(),
                dependencies,
                analysis,
                sourceCode,
                prompt,
                null,   // savedPath — preenchido pelo repositório depois
                null    // ollamaModel — injetado em executeAiTestGeneration ao reconstruir
        );
    }

    private String readSourceCode(Path sourceFile) {
        try {
            return Files.readString(sourceFile);
        } catch (IOException e) {
            throw new IllegalStateException("Erro ao ler código fonte da classe alvo: " + sourceFile, e);
        }
    }

    private List<String> combineDependencies(ClassAnalysis analysis) {
        LinkedHashSet<String> dependencies = new LinkedHashSet<>();
        dependencies.addAll(analysis.constructorDependencies());
        dependencies.addAll(analysis.fieldDependencies());
        return List.copyOf(dependencies);
    }

    private String sanitizeSourceCode(String sourceCode) {
        String normalized = sourceCode.replace("\r\n", "\n").trim();
        StringBuilder builder = new StringBuilder();
        boolean inBlockComment = false;

        for (String line : normalized.split("\n", -1)) {
            String current = line;
            if (inBlockComment) {
                int endIndex = current.indexOf("*/");
                if (endIndex < 0) {
                    continue;
                }
                current = current.substring(endIndex + 2);
                inBlockComment = false;
            }

            while (true) {
                int blockStart = current.indexOf("/*");
                int lineCommentStart = current.indexOf("//");

                if (blockStart >= 0 && (lineCommentStart < 0 || blockStart < lineCommentStart)) {
                    int blockEnd = current.indexOf("*/", blockStart + 2);
                    if (blockEnd >= 0) {
                        current = current.substring(0, blockStart) + current.substring(blockEnd + 2);
                        continue;
                    }

                    current = current.substring(0, blockStart);
                    inBlockComment = true;
                } else if (lineCommentStart >= 0) {
                    current = current.substring(0, lineCommentStart);
                }
                break;
            }

            String trimmed = current.stripTrailing();
            if (trimmed.isBlank()) {
                if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
                    builder.append('\n');
                }
                continue;
            }

            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(trimmed);
        }

        return builder.toString().trim();
    }

    private String buildPrompt(JavaClassCandidate candidate, String sourceCode, List<String> dependencies, ClassAnalysis analysis) {
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder();

        sb.append("Generate a JUnit 5 + Mockito unit test file for the Java class below.").append(nl);
        sb.append("Output ONLY raw Java code. No markdown, no explanation, no code fences.").append(nl);
        sb.append(nl);

        sb.append("TEST CLASS: ").append(candidate.className()).append("Test").append(nl);
        sb.append("PACKAGE: ").append(analysis.packageName()).append(nl);
        sb.append(nl);

        if (!dependencies.isEmpty()) {
            boolean fieldInjection = analysis.constructorDependencies().isEmpty() && !analysis.fieldDependencies().isEmpty();
            sb.append("REQUIRED CLASS SKELETON — copy this EXACTLY, then fill in the @Test methods:").append(nl);
            sb.append("@ExtendWith(MockitoExtension.class)").append(nl);
            sb.append("public class ").append(candidate.className()).append("Test {").append(nl);
            sb.append(nl);
            for (String dep : dependencies) {
                sb.append("    @Mock").append(nl);
                sb.append("    private ").append(dep).append(";").append(nl);
            }
            sb.append(nl);
            sb.append("    @InjectMocks").append(nl);
            sb.append("    private ").append(candidate.className()).append(" subject;").append(nl);
            sb.append(nl);
            sb.append("    // @Test methods go here").append(nl);
            sb.append("}").append(nl);
            if (fieldInjection) {
                sb.append("NOTE: field injection — Mockito injects @Mock fields into @InjectMocks via reflection.").append(nl);
                sb.append("Do NOT use @SpringBootTest or @Autowired in the test.").append(nl);
            }
            sb.append(nl);
        }

        List<String> testImports = filterTestRelevantImports(analysis.importedTypes());
        if (!testImports.isEmpty()) {
            sb.append("IMPORTS TO USE (exact FQNs from source class — do not invent others):").append(nl);
            for (String imp : testImports) {
                sb.append("  ").append(imp).append(nl);
            }
            sb.append(nl);
        }

        if (!analysis.publicMethods().isEmpty()) {
            sb.append("METHODS TO TEST (call subject.<method> with EXACTLY the parameter types listed — do not invent overloads):").append(nl);
            sb.append(buildPublicMethodsBlock(analysis)).append(nl);
            sb.append(nl);
        }

        if (!analysis.nonPublicMethodNames().isEmpty()) {
            sb.append("FORBIDDEN — DO NOT CALL FROM TEST (private/protected — causes compilation error):").append(nl);
            for (String name : analysis.nonPublicMethodNames()) {
                sb.append("  ").append(name).append("(...)").append(nl);
            }
            sb.append(nl);
        }

        if (sourceCode.contains("@Configuration") || sourceCode.contains("@ControllerAdvice")) {
            sb.append("NOTE: This is a @Configuration or @ControllerAdvice class. Special rules:").append(nl);
            sb.append("- Test ONLY the @Bean/@ExceptionHandler methods listed in METHODS TO TEST above.").append(nl);
            sb.append("- Do NOT mock Spring Security DSL builders (HttpSecurity, WebSecurityConfigurerAdapter, etc.).").append(nl);
            sb.append("- Do NOT use reflection (getDeclaredMethod, setAccessible, invoke) to inspect framework internals.").append(nl);
            sb.append("  Framework classes like DaoAuthenticationProvider have NO public getters for their internal state.").append(nl);
            sb.append("  DaoAuthenticationProvider.getUserDetailsService() and getPasswordEncoder() are PROTECTED — compilation error.").append(nl);
            sb.append("- For @Bean methods returning framework objects (AuthenticationProvider, PasswordEncoder, etc.):").append(nl);
            sb.append("  assert ONLY: `assertNotNull(result); assertTrue(result instanceof ExpectedType);`").append(nl);
            sb.append("  DO NOT cast to DaoAuthenticationProvider and call methods on it.").append(nl);
            sb.append("- For @Bean methods that accept a parameter (e.g. AuthenticationConfiguration): mock the parameter,").append(nl);
            sb.append("  stub its relevant methods, call the @Bean method on `subject`, assert the returned value is not null.").append(nl);
            if (sourceCode.contains("UserDetailsService")) {
                sb.append("- For @Bean UserDetailsService: call subject.userDetailsService() to get the lambda.").append(nl);
                sb.append("  Use thenAnswer to stub the repository (avoids generic type issues):").append(nl);
                sb.append("    org.springframework.security.core.userdetails.UserDetails u = mock(org.springframework.security.core.userdetails.UserDetails.class);").append(nl);
                sb.append("    when(repository.findByUsername(anyString())).thenAnswer(inv -> java.util.Optional.of(u));").append(nl);
                sb.append("    UserDetails result = subject.userDetailsService().loadUserByUsername(\"user\");").append(nl);
                sb.append("    assertNotNull(result);").append(nl);
                sb.append("  For the not-found case: `when(repository.findByUsername(anyString())).thenReturn(Optional.empty());`").append(nl);
                sb.append("  then `assertThrows(UsernameNotFoundException.class, () -> subject.userDetailsService().loadUserByUsername(\"x\"));`").append(nl);
            }
            if (sourceCode.contains("@ControllerAdvice") || sourceCode.contains("BindingResult") || sourceCode.contains("ConstraintViolation")) {
                sb.append("CRITICAL — @ControllerAdvice handler type mapping: each handler takes a SPECIFIC exception type.").append(nl);
                sb.append("  NEVER pass the wrong exception type to a handler. Match the @ExceptionHandler annotation:").append(nl);
                sb.append("  subject.handle01(methodArgumentNotValidException) — MethodArgumentNotValidException ONLY").append(nl);
                sb.append("  subject.handle02(constraintViolationException)   — ConstraintViolationException ONLY").append(nl);
                sb.append("  subject.handle03(anyException)                   — Exception ONLY").append(nl);
                sb.append(nl);
                sb.append("EXACT PATTERN for handle01 (@ExceptionHandler(MethodArgumentNotValidException.class)):").append(nl);
                sb.append("    @Mock private MethodArgumentNotValidException mave;").append(nl);
                sb.append("    // In test:").append(nl);
                sb.append("    org.springframework.validation.BindingResult br = mock(org.springframework.validation.BindingResult.class);").append(nl);
                sb.append("    org.springframework.validation.FieldError fe = mock(org.springframework.validation.FieldError.class);").append(nl);
                sb.append("    when(fe.getField()).thenReturn(\"field\"); when(fe.getDefaultMessage()).thenReturn(\"error\");").append(nl);
                sb.append("    when(br.getFieldErrors()).thenReturn(java.util.List.of(fe));").append(nl);
                sb.append("    when(mave.getBindingResult()).thenReturn(br);  // stub BindingResult SEPARATELY").append(nl);
                sb.append("    ResponseEntity<?> r = subject.handle01(mave);  // pass mave (MethodArgumentNotValidException)").append(nl);
                sb.append("    assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());").append(nl);
                sb.append("    assertEquals(\"error\", ((java.util.Map<?,?>)r.getBody()).get(\"field\"));").append(nl);
                sb.append(nl);
                sb.append("EXACT PATTERN for handle02 (@ExceptionHandler(ConstraintViolationException.class)):").append(nl);
                sb.append("    @Mock private ConstraintViolationException cve;").append(nl);
                sb.append("    // In test — use RETURNS_DEEP_STUBS to allow chained calls:").append(nl);
                sb.append("    jakarta.validation.ConstraintViolation<?> v = mock(jakarta.validation.ConstraintViolation.class, Answers.RETURNS_DEEP_STUBS);").append(nl);
                sb.append("    when(v.getPropertyPath().toString()).thenReturn(\"field\");  // safe with RETURNS_DEEP_STUBS").append(nl);
                sb.append("    when(v.getMessage()).thenReturn(\"error\");").append(nl);
                sb.append("    when(cve.getConstraintViolations()).thenReturn(java.util.Set.of(v));").append(nl);
                sb.append("    ResponseEntity<?> r = subject.handle02(cve);  // pass cve (ConstraintViolationException)").append(nl);
                sb.append("    assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());").append(nl);
                sb.append("    assertEquals(\"error\", ((java.util.Map<?,?>)r.getBody()).get(\"field\"));").append(nl);
                sb.append(nl);
                sb.append("  NEVER do: `when(violation.getPropertyPath()).thenReturn(mock(Path.class))` (no toString stub — wrong key!)").append(nl);
                sb.append("  NEVER do: `when(ex.getBindingResult().getFieldErrors())...` — NPE!").append(nl);
                sb.append("  NEVER do: `new FieldError(..., violation)` — FieldError takes String args only.").append(nl);
            }
            sb.append(nl);
        }

        sb.append("COVERAGE RULES per method:").append(nl);
        sb.append("- assert the actual return value (not just non-null)").append(nl);
        sb.append("- CRITICAL — assert ONLY what the code actually does: read the method body carefully.").append(nl);
        sb.append("  If the method always returns the same value (e.g. `return new ResponseEntity<>(x, HttpStatus.OK)`),").append(nl);
        sb.append("  assert exactly that value. Do NOT assert status codes or return values that the code cannot produce.").append(nl);
        sb.append("- cover each branch (if/else, orElse, ternary, guard clause)").append(nl);
        if (analysis.usesOptional()) {
            sb.append("- mock Optional.of(...) for found and Optional.empty() for not-found paths").append(nl);
            sb.append("- when a method calls orElse(null) and then uses the result without null check,").append(nl);
            sb.append("  the not-found branch throws NullPointerException — use assertThrows(NullPointerException.class, ...) for it").append(nl);
        }
        if (analysis.usesExceptions()) {
            sb.append("- use assertThrows for exception paths").append(nl);
        }
        sb.append("- use verify() on mocks when the observable result is a collaborator call").append(nl);
        sb.append("- CRITICAL — @BeforeEach Mockito stubs: With @ExtendWith(MockitoExtension.class) strict mode, EVERY stub set up in @BeforeEach").append(nl);
        sb.append("  MUST be used in EVERY @Test method. If a stub is only needed by one test, put it INSIDE that @Test method, not in @BeforeEach.").append(nl);
        sb.append("  Unused @BeforeEach stubs cause UnnecessaryStubbing and fail every test that doesn't use them.").append(nl);
        sb.append("  PREFERRED: make each @Test method fully self-contained with its own stubs.").append(nl);
        sb.append("- CRITICAL — chained mock calls in when(): NEVER write `when(mock.getX().getY()).thenReturn(v)`.").append(nl);
        sb.append("  `mock.getX()` returns null by default, then `null.getY()` throws NullPointerException.").append(nl);
        sb.append("  ALWAYS stub each step separately:").append(nl);
        sb.append("    SomeType x = mock(SomeType.class);").append(nl);
        sb.append("    when(mock.getX()).thenReturn(x);").append(nl);
        sb.append("    when(x.getY()).thenReturn(v);").append(nl);
        sb.append("  Common examples that require this pattern:").append(nl);
        sb.append("  * MethodArgumentNotValidException: `BindingResult br = mock(BindingResult.class);`").append(nl);
        sb.append("    `when(ex.getBindingResult()).thenReturn(br);`").append(nl);
        sb.append("    `when(br.getFieldErrors()).thenReturn(List.of(fieldError));`").append(nl);
        sb.append("  * ConstraintViolation: `Path path = mock(Path.class);`").append(nl);
        sb.append("    `when(violation.getPropertyPath()).thenReturn(path);`").append(nl);
        sb.append("    `when(path.toString()).thenReturn(\"fieldName\");`").append(nl);
        sb.append("- CRITICAL — only test PUBLIC methods: NEVER call private or protected methods directly").append(nl);
        sb.append("  from the test class. If a method is private/protected it cannot be called from outside").append(nl);
        sb.append("  the class and any such call will cause a compilation error.").append(nl);
        sb.append("- CRITICAL — exception stubs: use `new RuntimeException()` (unchecked) in thenThrow/doThrow").append(nl);
        sb.append("  UNLESS the mocked method's signature explicitly declares `throws SomeCheckedException`.").append(nl);
        sb.append("  Throwing a checked exception from a method that doesn't declare it causes a MockitoException.").append(nl);
        sb.append("- CRITICAL — argument matchers: if the method body creates a NEW object internally").append(nl);
        sb.append("  (e.g. `Autor autor = new Autor(); autor.setId(idAutor); repo.findByAutor(autor);`)").append(nl);
        sb.append("  you CANNOT match that instance in the test. Use `any(Autor.class)` in both").append(nl);
        sb.append("  `when(repo.findByAutor(any(Autor.class))).thenReturn(...)` and").append(nl);
        sb.append("  `verify(repo).findByAutor(any(Autor.class))`. Never create a matching instance").append(nl);
        sb.append("  to stub an internally-created object — it will always fail without equals().").append(nl);
        sb.append("- CRITICAL — test data: use the no-arg constructor (`new Type()`) to create test objects,").append(nl);
        sb.append("  then use setters. Do NOT invent multi-arg constructors; they only exist if @AllArgsConstructor").append(nl);
        sb.append("  is visible in the source. Wrong constructors cause compilation errors.").append(nl);
        sb.append("- CRITICAL — non-null fields: when a method body does `obj.setX(param.getX())`, the test param").append(nl);
        sb.append("  MUST have X set to a real non-null value before the call (e.g. `param.setX(\"value\")` for String,").append(nl);
        sb.append("  `param.setX(2023)` for int). Forgetting to set a String field leaves it null, which causes").append(nl);
        sb.append("  NullPointerException in setters that enforce `@Nonnull` constraints.").append(nl);
        if (sourceCode.contains("generateToken") && sourceCode.contains("extractUsername")) {
            sb.append("CRITICAL — JWT token testing: when testing extractUsername, extractClaim, isTokenValid:").append(nl);
            sb.append("- Do NOT create tokens manually with Jwts.builder() — the internal key derivation may differ.").append(nl);
            sb.append("- ALWAYS create test tokens using the service's own `subject.generateToken(user)` method.").append(nl);
            sb.append("  Flow: `String token = subject.generateToken(user); subject.extractUsername(token);`").append(nl);
            sb.append("- The `generateToken` call on the SAME `subject` instance uses the SAME key → tokens are valid.").append(nl);
            sb.append(nl);
        }
        sb.append("- CRITICAL — collaborator return types: infer the return type of each collaborator call from the").append(nl);
        sb.append("  LOCAL VARIABLE TYPE in the method body. If the body says `Livro x = service.findById(id)`,").append(nl);
        sb.append("  then `service.findById` returns `Livro` — stub with `when(service.findById(any())).thenReturn(new Livro())`.").append(nl);
        sb.append("  If the body says `Optional<Livro> x = service.findById(id)`, it returns `Optional<Livro>`.").append(nl);
        sb.append("  Do NOT assume Optional when the variable type is the plain entity. Wrong return type causes compilation error.").append(nl);
        sb.append("- CRITICAL — entity field names: use ONLY setters that exist on the entity. Do not invent field names").append(nl);
        sb.append("  like `setAutorId()` unless that exact field appears in the source. Check the SOURCE CLASS imports and body.").append(nl);
        sb.append("  If the entity field is a foreign key stored as a Long, use `setAutor(autorObject)` or `setAutorId(id)`").append(nl);
        sb.append("  ONLY if those names actually appear in the entity source. When in doubt, use the no-arg constructor alone.").append(nl);
        sb.append("- CRITICAL — argument transformation: if the method transforms an argument before passing it to").append(nl);
        sb.append("  a collaborator (e.g. `String token = header.substring(7); service.extractUsername(token);`),").append(nl);
        sb.append("  the collaborator receives the TRANSFORMED value, not the original. The stub MUST reflect this.").append(nl);
        sb.append("  SAFE approach: use `anyString()` or `any(Type.class)` in stubs for collaborators that receive").append(nl);
        sb.append("  internally-transformed values, so the stub matches regardless of the exact transformed content.").append(nl);
        sb.append("- CRITICAL — exact method signatures: call each method with EXACTLY the parameter count and types").append(nl);
        sb.append("  shown in METHODS TO TEST. Do NOT add extra parameters or change types. A method listed as").append(nl);
        sb.append("  `extractUsername(String token)` takes ONE String — do not call it with two arguments.").append(nl);
        sb.append("- use descriptive test method names").append(nl);
        sb.append(nl);

        sb.append("SOURCE CLASS:").append(nl);
        sb.append(sourceCode);

        return sb.toString();
    }

    private List<String> filterTestRelevantImports(List<String> imports) {
        return imports.stream()
                .filter(imp -> imp != null && !imp.isBlank())
                .filter(imp -> !imp.startsWith("org.springframework.beans.factory.annotation."))
                .filter(imp -> !imp.startsWith("org.springframework.stereotype."))
                .filter(imp -> !imp.startsWith("org.springframework.web.bind.annotation."))
                .filter(imp -> !imp.startsWith("jakarta.persistence."))
                .filter(imp -> !imp.startsWith("javax.persistence."))
                .toList();
    }

    private String buildPublicMethodsBlock(ClassAnalysis analysis) {
        StringBuilder builder = new StringBuilder();
        List<MethodAnalysis> methods = analysis.publicMethods();
        for (int i = 0; i < methods.size(); i++) {
            MethodAnalysis method = methods.get(i);
            String signature = method.methodName() + "(" + String.join(", ", method.parameters()) + ") -> " + method.returnType();
            builder.append(i + 1).append(". ").append(signature);
            if (!method.thrownExceptions().isEmpty()) {
                builder.append(" throws ").append(String.join(", ", method.thrownExceptions()));
            }
            if (!method.methodBody().isBlank()) {
                builder.append(nl).append("   body:");
                for (String line : method.methodBody().split("\\R", -1)) {
                    builder.append(nl).append("   ").append(line);
                }
            }
            if (i < methods.size() - 1) {
                builder.append(nl);
            }
        }
        return builder.toString().stripTrailing();
    }

    private static final String nl = System.lineSeparator();
}
