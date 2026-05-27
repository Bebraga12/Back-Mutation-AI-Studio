package com.mutation.mutation_ai_studio.application.usecase;

import com.mutation.mutation_ai_studio.application.port.out.AiTestGeneratorPort;
import com.mutation.mutation_ai_studio.domain.model.ClassTestPrompt;
import com.mutation.mutation_ai_studio.domain.model.GeneratedTestCandidate;
import com.mutation.mutation_ai_studio.domain.model.TestExecutionFeedback;
import org.springframework.stereotype.Service;

@Service
public class RefineGeneratedTestService {

    private final AiTestGeneratorPort aiTestGeneratorPort;

    public RefineGeneratedTestService(AiTestGeneratorPort aiTestGeneratorPort) {
        this.aiTestGeneratorPort = aiTestGeneratorPort;
    }

    public GeneratedTestCandidate refine(ClassTestPrompt prompt,
                                         GeneratedTestCandidate previousCandidate,
                                         TestExecutionFeedback feedback) {
        boolean hasCannotFindSymbol = feedback.errors().stream()
                .anyMatch(error -> error.toLowerCase().contains("cannot find symbol"));
        boolean hasWrongTarget = feedback.errors().stream()
                .anyMatch(error -> error.toLowerCase().contains("troca indevida da classe alvo")
                        || error.toLowerCase().contains("não referencia explicitamente a classe alvo")
                        || error.toLowerCase().contains("classe de teste gerada não corresponde")
                        || error.toLowerCase().contains("@autowired")
                        || error.toLowerCase().contains("@springboottest"));
        boolean hasMissingImports = feedback.errors().stream()
                .anyMatch(error -> error.toLowerCase().contains("sem import explícito"));
        boolean hasPrivateAccess = feedback.errors().stream()
                .anyMatch(error -> error.toLowerCase().contains("has private access")
                        || error.toLowerCase().contains("has protected access")
                        || error.toLowerCase().contains("is not public")
                        || (error.toLowerCase().contains("private") && error.toLowerCase().contains("access")));
        boolean hasCheckedExceptionStub = feedback.errors().stream()
                .anyMatch(error -> error.toLowerCase().contains("checked exception is invalid")
                        || (error.toLowerCase().contains("mockitoexception") && error.toLowerCase().contains("exception")));
        boolean hasInvalidUseOfMatchers = feedback.errors().stream()
                .anyMatch(error -> error.toLowerCase().contains("invaliduseofmatchers")
                        || error.toLowerCase().contains("invalid use of argument matchers"));
        boolean hasPotentialStubbingProblem = feedback.errors().stream()
                .anyMatch(error -> error.toLowerCase().contains("potentialstubbingproblem")
                        || error.toLowerCase().contains("potential stubbing problem"));
        boolean hasUnnecessaryStubbing = feedback.errors().stream()
                .anyMatch(error -> error.toLowerCase().contains("unnecessarystubbing")
                        || error.toLowerCase().contains("unnecessary stubbing"));
        boolean hasUnhandledException = feedback.errors().stream()
                .anyMatch(error -> (error.toLowerCase().contains("» runtime")
                        || error.toLowerCase().contains("» illegalargument")
                        || error.toLowerCase().contains("» illegalstate"))
                        && !error.toLowerCase().contains("assertthrows"));
        boolean hasAssertionMismatch = feedback.errors().stream()
                .anyMatch(error -> error.toLowerCase().contains("expected:") || error.toLowerCase().contains("but was:"));
        boolean hasWantedButNotInvoked = feedback.errors().stream()
                .anyMatch(error -> error.toLowerCase().contains("wanted but not invoked"));
        // Distinguish: assertion on HTTP status (wrong behavior modeled) vs. argument matcher issue
        boolean hasStatusCodeMismatch = hasAssertionMismatch && feedback.errors().stream()
                .anyMatch(error -> error.toLowerCase().contains("ok") && error.toLowerCase().contains("expected:")
                        || error.toLowerCase().contains("unauthorized") || error.toLowerCase().contains("forbidden")
                        || error.toLowerCase().contains("internal_server_error")
                        || (error.toLowerCase().contains("200") && error.toLowerCase().contains("expected:")));
        boolean likelyArgumentMatcherIssue = (hasAssertionMismatch || hasWantedButNotInvoked || hasPotentialStubbingProblem)
                && !hasCannotFindSymbol && !hasWrongTarget && !hasStatusCodeMismatch;
        boolean hasNullPointerException = feedback.errors().stream()
                .anyMatch(error -> error.toLowerCase().contains("nullpointer")
                        || (error.toLowerCase().contains("nonnull") && error.toLowerCase().contains("null"))
                        || error.toLowerCase().contains("non-null but is null")
                        || error.toLowerCase().contains("marked non-null"));
        boolean hasConstraintViolationNpe = hasNullPointerException && feedback.errors().stream()
                .anyMatch(error -> error.toLowerCase().contains("getpropertypath")
                        || error.toLowerCase().contains("constraintviolation")
                        || error.toLowerCase().contains("jakarta.validation"));
        boolean hasBindingResultNpe = hasNullPointerException && feedback.errors().stream()
                .anyMatch(error -> error.toLowerCase().contains("getbindingresult")
                        || error.toLowerCase().contains("bindingresult")
                        || error.toLowerCase().contains("getfielderrors")
                        || error.toLowerCase().contains("methodargumentnotvalidexception"));
        boolean hasWrongMethodSignature = feedback.errors().stream()
                .anyMatch(error -> error.toLowerCase().contains("cannot be applied to given types")
                        || error.toLowerCase().contains("actual and formal argument lists differ in length")
                        || (error.toLowerCase().contains("method") && error.toLowerCase().contains("cannot be applied")));
        boolean hasWrongReturnTypeStub = feedback.errors().stream()
                .anyMatch(error -> error.toLowerCase().contains("no suitable method found for thenreturn")
                        || (error.toLowerCase().contains("thenreturn") && error.toLowerCase().contains("no suitable")));
        boolean hasCannotFindSetter = hasCannotFindSymbol && feedback.errors().stream()
                .anyMatch(error -> error.toLowerCase().contains("symbol:   method set")
                        || (error.toLowerCase().contains("cannot find symbol") && error.toLowerCase().contains("set")));
        boolean hasUnstubbbedMockToString = hasAssertionMismatch && feedback.errors().stream()
                .anyMatch(error -> error.contains("Mock for") && (error.contains("=error") || error.contains("=") && error.contains("Mock for")));
        boolean hasJwtKeyAccess = feedback.errors().stream()
                .anyMatch(error -> error.toLowerCase().contains("getsecretkey")
                        || error.toLowerCase().contains("getsigningkey")
                        || (error.toLowerCase().contains("has private access") && error.toLowerCase().contains("key"))
                        || (error.toLowerCase().contains("cannot find symbol") && error.toLowerCase().contains("key")));
        boolean hasIncompatibleTypes = feedback.errors().stream()
                .anyMatch(error -> error.toLowerCase().contains("incompatible types"));
        boolean hasWrongHandlerType = hasIncompatibleTypes && feedback.errors().stream()
                .anyMatch(error -> error.toLowerCase().contains("constraintviolation") || error.toLowerCase().contains("methodargumentnotvalid"));
        // Extract entity class name from "Optional<com.example.Entity> is not applicable" errors
        String wrongReturnEntityHint = feedback.errors().stream()
                .filter(e -> e.contains("thenReturn") && e.contains("Optional<"))
                .map(e -> {
                    int start = e.indexOf("Optional<") + "Optional<".length();
                    int end = e.indexOf(">", start);
                    if (start > 0 && end > start) {
                        String fqn = e.substring(start, end).trim();
                        return fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
                    }
                    return "";
                })
                .filter(s -> !s.isBlank())
                .findFirst().orElse("");

        String refinementPrompt = prompt.prompt()
                + System.lineSeparator()
                + System.lineSeparator()
                + "O teste anterior falhou na execução real do Maven." + System.lineSeparator()
                + "Corrija o arquivo abaixo com base nos erros reais." + System.lineSeparator()
                + "Nome esperado da classe de teste: " + previousCandidate.testClassName() + System.lineSeparator()
                + "Erros reais:" + System.lineSeparator()
                + String.join(System.lineSeparator(), feedback.errors()) + System.lineSeparator()
                + System.lineSeparator()
                + buildSymbolGuidance(prompt, hasCannotFindSymbol, hasWrongTarget, hasMissingImports,
                        likelyArgumentMatcherIssue, hasNullPointerException,
                        hasPrivateAccess, hasCheckedExceptionStub, hasStatusCodeMismatch,
                        hasInvalidUseOfMatchers, hasUnnecessaryStubbing, hasUnhandledException,
                        hasConstraintViolationNpe, hasWrongMethodSignature,
                        hasWrongReturnTypeStub, hasCannotFindSetter, hasBindingResultNpe,
                        wrongReturnEntityHint, hasUnstubbbedMockToString, hasJwtKeyAccess,
                        hasWrongHandlerType) + System.lineSeparator()
                + System.lineSeparator()
                + "Teste anterior:" + System.lineSeparator()
                + previousCandidate.sourceCode();

        ClassTestPrompt refinementRequest = new ClassTestPrompt(
                prompt.className(),
                prompt.fullyQualifiedName(),
                prompt.relativePath(),
                prompt.dependencies(),
                prompt.analysis(),
                prompt.sourceCode(),
                refinementPrompt,
                prompt.savedPath()
        );

        String refinedCode = GeneratedTestSourceNormalizer.normalize(
                aiTestGeneratorPort.generateTestCode(refinementRequest),
                refinementRequest
        );
        return new GeneratedTestCandidate(
                refinementRequest,
                previousCandidate.className(),
                previousCandidate.fullyQualifiedName(),
                previousCandidate.testClassName(),
                refinedCode,
                previousCandidate.savedPath()
        );
    }

    private String buildSymbolGuidance(ClassTestPrompt prompt, boolean hasCannotFindSymbol, boolean hasWrongTarget,
                                        boolean hasMissingImports, boolean likelyArgumentMatcherIssue,
                                        boolean hasNullPointerException, boolean hasPrivateAccess,
                                        boolean hasCheckedExceptionStub, boolean hasStatusCodeMismatch,
                                        boolean hasInvalidUseOfMatchers, boolean hasUnnecessaryStubbing,
                                        boolean hasUnhandledException, boolean hasConstraintViolationNpe,
                                        boolean hasWrongMethodSignature, boolean hasWrongReturnTypeStub,
                                        boolean hasCannotFindSetter, boolean hasBindingResultNpe,
                                        String wrongReturnEntityHint, boolean hasUnstubbbedMockToString,
                                        boolean hasJwtKeyAccess, boolean hasWrongHandlerType) {
        if (hasWrongTarget) {
            return "Orientações obrigatórias para corrigir o alvo do teste:" + System.lineSeparator()
                    + "- gere teste exclusivamente para a classe alvo " + prompt.className() + System.lineSeparator()
                    + "- o nome da classe de teste deve ser exatamente " + prompt.className() + "Test" + System.lineSeparator()
                    + "- não troque service por controller, nem controller por service" + System.lineSeparator()
                    + "- não use @SpringBootTest" + System.lineSeparator()
                    + "- não use @Autowired" + System.lineSeparator()
                    + "- use teste unitário puro com Mockito e a classe alvo correta";
        }

        if (hasWrongHandlerType) {
            return "The test passed the WRONG exception type to an @ExceptionHandler method — type mismatch." + System.lineSeparator()
                    + "MANDATORY FIXES:" + System.lineSeparator()
                    + "- Each @ExceptionHandler accepts ONLY its declared exception type — do NOT mix them." + System.lineSeparator()
                    + "- RULE: `subject.handle01(...)` must receive a MethodArgumentNotValidException instance/mock." + System.lineSeparator()
                    + "  `subject.handle02(...)` must receive a ConstraintViolationException instance/mock." + System.lineSeparator()
                    + "  `subject.handle03(...)` must receive an Exception instance/mock." + System.lineSeparator()
                    + "- For handle02, use RETURNS_DEEP_STUBS to avoid NPE on chained calls:" + System.lineSeparator()
                    + "    @Mock private ConstraintViolationException cve;" + System.lineSeparator()
                    + "    ConstraintViolation<?> v = mock(ConstraintViolation.class, Answers.RETURNS_DEEP_STUBS);" + System.lineSeparator()
                    + "    when(v.getPropertyPath().toString()).thenReturn(\"field\");" + System.lineSeparator()
                    + "    when(v.getMessage()).thenReturn(\"error\");" + System.lineSeparator()
                    + "    when(cve.getConstraintViolations()).thenReturn(Set.of(v));" + System.lineSeparator()
                    + "    ResponseEntity<?> r = subject.handle02(cve);  // cve, NOT mave" + System.lineSeparator()
                    + "- Also: FieldError constructor is (String objectName, String field, String defaultMessage) — all Strings." + System.lineSeparator()
                    + "  Do NOT pass a ConstraintViolation object as any FieldError constructor argument.";
        }

        if (hasWrongReturnTypeStub) {
            String entityDesc = wrongReturnEntityHint.isBlank() ? "ConcreteEntity" : wrongReturnEntityHint;
            return "The test called thenReturn() with the wrong Optional generic type." + System.lineSeparator()
                    + "MANDATORY FIXES:" + System.lineSeparator()
                    + "- The repository method returns Optional<" + entityDesc + ">,"
                    + " not Optional<UserDetails> or Optional<Object>." + System.lineSeparator()
                    + "- OPTION A (preferred) — use thenAnswer to bypass compile-time generic type check:" + System.lineSeparator()
                    + "    org.springframework.security.core.userdetails.UserDetails u = mock(org.springframework.security.core.userdetails.UserDetails.class);" + System.lineSeparator()
                    + "    when(repository.findByUsername(anyString())).thenAnswer(inv -> java.util.Optional.of(u));" + System.lineSeparator()
                    + "    // For not-found: when(repo.findByUsername(anyString())).thenReturn(java.util.Optional.empty());" + System.lineSeparator()
                    + "- OPTION B — instantiate the real entity class " + entityDesc + " and use it:" + System.lineSeparator()
                    + (wrongReturnEntityHint.isBlank()
                        ? "    Use the entity class name shown in the error and stub with Optional.of(new EntityClass())."
                        : "    when(repository.findByUsername(anyString())).thenReturn(java.util.Optional.of(new " + entityDesc + "()));") + System.lineSeparator()
                    + "- For null-check branches (`if (x == null)`): stub with `.thenReturn(null)` not Optional.empty()." + System.lineSeparator()
                    + "- For Optional branches (`if (x.isPresent())`): stub with Optional.of(entity) and Optional.empty().";
        }

        if (hasCannotFindSetter) {
            return "The test called a setter method that does not exist on the entity." + System.lineSeparator()
                    + "MANDATORY FIXES:" + System.lineSeparator()
                    + "- Only use setters that are VISIBLE in the entity source (shown in SOURCE CLASS imports/body)." + System.lineSeparator()
                    + "- Do NOT invent setter names like `setAutorId()` unless that field literally appears in the source." + System.lineSeparator()
                    + "- Prefer using just the no-arg constructor and only setting fields you are CERTAIN exist." + System.lineSeparator()
                    + "- Look at the imports in the source class for entity field clues — if the entity uses a `@ManyToOne Autor autor`, the setter is `setAutor(autorObject)`, not `setAutorId(long)`.";
        }

        if (likelyArgumentMatcherIssue) {
            return "The test failed with a Mockito argument matching problem (assertion mismatch, PotentialStubbingProblem, or wanted-but-not-invoked)." + System.lineSeparator()
                    + "MANDATORY FIXES:" + System.lineSeparator()
                    + "- Look at every method body shown in METHODS TO TEST above." + System.lineSeparator()
                    + "- If a method creates a NEW object internally (e.g. `Autor autor = new Autor(); autor.setId(id);`)" + System.lineSeparator()
                    + "  and passes it to a repository, you CANNOT match that instance in the test — there is no equals()." + System.lineSeparator()
                    + "- PotentialStubbingProblem means your stub was configured but NEVER matched — change the stub to use `any(Type.class)`." + System.lineSeparator()
                    + "- Replace: `when(repo.findByAutor(autor)).thenReturn(...)` and `verify(repo).findByAutor(autor)`" + System.lineSeparator()
                    + "  with:    `when(repo.findByAutor(any(Autor.class))).thenReturn(...)` and `verify(repo).findByAutor(any(Autor.class))`" + System.lineSeparator()
                    + "- Apply this pattern for EVERY collaborator call where the argument is constructed inside the method under test." + System.lineSeparator()
                    + "- Do NOT create a matching instance variable in the test to stub an internally-created object." + System.lineSeparator()
                    + "- When in doubt about whether equals() exists, prefer `any(Type.class)` over a specific instance.";
        }

        if (hasWrongMethodSignature && !hasCannotFindSymbol) {
            String methodsBlock = prompt.analysis().publicMethods().stream()
                    .map(m -> "  " + m.methodName() + "(" + String.join(", ", m.parameters()) + ") -> " + m.returnType())
                    .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
            return "The test called a method with the WRONG number or type of arguments." + System.lineSeparator()
                    + "MANDATORY FIXES:" + System.lineSeparator()
                    + "- Check EVERY call to `subject.<method>()` — use EXACTLY the signature listed in METHODS TO TEST." + System.lineSeparator()
                    + "- Do NOT add extra parameters (e.g. a Function argument) to a method that only takes one String." + System.lineSeparator()
                    + "- Do NOT confuse different methods: `extractUsername(String)` and `extractClaim(String, Function)` are different." + System.lineSeparator()
                    + "- The correct public method signatures for " + prompt.className() + " are:" + System.lineSeparator()
                    + methodsBlock + System.lineSeparator()
                    + "- Also apply `anyString()` in stubs for collaborators that receive internally-transformed values" + System.lineSeparator()
                    + "  (e.g. if the method strips 'Bearer ' before calling jwtService, stub with anyString(), not the raw header).";
        }

        if (hasBindingResultNpe) {
            return "The test threw a NullPointerException on BindingResult/FieldErrors — you chained mock calls in when()." + System.lineSeparator()
                    + "MANDATORY FIX — never write `when(ex.getBindingResult().getFieldErrors()).thenReturn(...)`:" + System.lineSeparator()
                    + "  `ex.getBindingResult()` returns null on a default mock → `.getFieldErrors()` NPEs." + System.lineSeparator()
                    + "CORRECT pattern:" + System.lineSeparator()
                    + "    org.springframework.validation.BindingResult br = mock(org.springframework.validation.BindingResult.class);" + System.lineSeparator()
                    + "    when(methodArgumentNotValidException.getBindingResult()).thenReturn(br);" + System.lineSeparator()
                    + "    when(br.getFieldErrors()).thenReturn(java.util.List.of(fieldError));" + System.lineSeparator()
                    + "Apply this fix EVERYWHERE you chain calls on the exception mock.";
        }

        if (hasJwtKeyAccess) {
            return "The test tried to access the JWT signing key via a method that does not exist (getSecretKey(), getKey(), etc.)." + System.lineSeparator()
                    + "MANDATORY FIXES — do NOT try to access the internal signing key:" + System.lineSeparator()
                    + "- To test `isTokenValid`: generate a token with `subject.generateToken(user)`, then call `subject.isTokenValid(token, userDetails)`." + System.lineSeparator()
                    + "  For the 'invalid username' case: create TWO UserDetails mocks with different usernames:" + System.lineSeparator()
                    + "    UserDetails correctUser = mock(UserDetails.class); when(correctUser.getUsername()).thenReturn(\"testUser\");" + System.lineSeparator()
                    + "    UserDetails wrongUser = mock(UserDetails.class); when(wrongUser.getUsername()).thenReturn(\"otherUser\");" + System.lineSeparator()
                    + "    String token = subject.generateToken(correctUser);  // generate with correctUser" + System.lineSeparator()
                    + "    assertTrue(subject.isTokenValid(token, correctUser));" + System.lineSeparator()
                    + "    assertFalse(subject.isTokenValid(token, wrongUser));" + System.lineSeparator()
                    + "- To test `extractUsername` or `extractClaim`: generate token with `subject.generateToken(user)`," + System.lineSeparator()
                    + "  then call `subject.extractUsername(token)` — no manual Jwts.builder() needed." + System.lineSeparator()
                    + "- Do NOT call Jwts.builder().signWith(subject.getSecretKey()) — use the service's own methods.";
        }

        if (hasUnstubbbedMockToString) {
            return "The test assertion failed because a mock's toString() was used as a map key without being stubbed." + System.lineSeparator()
                    + "Error shows something like: `{Mock for Path, hashCode: 123=error}` instead of `{field=error}`." + System.lineSeparator()
                    + "MANDATORY FIX — when a mock is used as a key (e.g. via path.toString()), stub toString() explicitly:" + System.lineSeparator()
                    + "    jakarta.validation.Path path = mock(jakarta.validation.Path.class);" + System.lineSeparator()
                    + "    when(path.toString()).thenReturn(\"field\");  // ← ADD THIS" + System.lineSeparator()
                    + "    when(violation.getPropertyPath()).thenReturn(path);" + System.lineSeparator()
                    + "  Then assert: `assertEquals(\"error\", body.get(\"field\"))` (use the STRING KEY you set in toString())." + System.lineSeparator()
                    + "  NEVER do: `when(violation.getPropertyPath()).thenReturn(mock(Path.class))` inline — that mock has no stubbed toString().";
        }

        if (hasConstraintViolationNpe) {
            return "The test threw a NullPointerException inside a jakarta.validation.ConstraintViolation chain." + System.lineSeparator()
                    + "MANDATORY FIX — never chain calls on a mock in when():" + System.lineSeparator()
                    + "- WRONG: `when(violation.getPropertyPath().toString()).thenReturn(\"field\")` — getPropertyPath() returns null → NPE" + System.lineSeparator()
                    + "- CORRECT (option A — separate stubs):" + System.lineSeparator()
                    + "    jakarta.validation.Path path = mock(jakarta.validation.Path.class);" + System.lineSeparator()
                    + "    when(violation.getPropertyPath()).thenReturn(path);" + System.lineSeparator()
                    + "    when(path.toString()).thenReturn(\"field\");" + System.lineSeparator()
                    + "- CORRECT (option B — deep stubs on the violation mock):" + System.lineSeparator()
                    + "    ConstraintViolation<?> violation = mock(ConstraintViolation.class, Answers.RETURNS_DEEP_STUBS);" + System.lineSeparator()
                    + "    when(violation.getPropertyPath().toString()).thenReturn(\"field\");" + System.lineSeparator()
                    + "    when(violation.getMessage()).thenReturn(\"must not be blank\");" + System.lineSeparator()
                    + "  (import org.mockito.Answers — already in scope)" + System.lineSeparator()
                    + "- Apply option A or B to EVERY place in the test that chains calls on a ConstraintViolation mock.";
        }

        if (hasNullPointerException && !hasCannotFindSymbol) {
            return "The test threw a NullPointerException — two common causes:" + System.lineSeparator()
                    + System.lineSeparator()
                    + "CAUSE 1 — chained mock calls in when(): `when(mock.getX().getY()).thenReturn(v)` — `getX()` returns null, `getY()` NPEs." + System.lineSeparator()
                    + "FIX 1: stub each step separately:" + System.lineSeparator()
                    + "  SomeType x = mock(SomeType.class);" + System.lineSeparator()
                    + "  when(mock.getX()).thenReturn(x);" + System.lineSeparator()
                    + "  when(x.getY()).thenReturn(v);" + System.lineSeparator()
                    + "OR use @Mock(answer = Answers.RETURNS_DEEP_STUBS) on the mock field." + System.lineSeparator()
                    + System.lineSeparator()
                    + "CAUSE 2 — test data missing non-null field values: wherever `param.getX()` is passed to a setter annotated" + System.lineSeparator()
                    + "  with @Nonnull, set the value first: `param.setX(\"value\")` for String, `param.setX(2023)` for int." + System.lineSeparator()
                    + "FIX 2: set ALL fields that the method reads from a parameter object before calling the method.";
        }

        if (hasUnhandledException) {
            return "A test method threw an uncaught exception (RuntimeException, IllegalArgumentException, etc.)." + System.lineSeparator()
                    + "MANDATORY FIXES:" + System.lineSeparator()
                    + "- Re-read the method body in METHODS TO TEST. If the method has NO try/catch, any exception thrown" + System.lineSeparator()
                    + "  by a mock propagates up and causes the test to ERROR (not fail)." + System.lineSeparator()
                    + "- OPTION A: If you want to test that an exception propagates, use assertThrows:" + System.lineSeparator()
                    + "  `assertThrows(RuntimeException.class, () -> subject.method(args));`" + System.lineSeparator()
                    + "- OPTION B: If the method has no error-handling path, remove the exception test entirely." + System.lineSeparator()
                    + "  Only test paths that actually exist in the method body." + System.lineSeparator()
                    + "- OPTION C: If the exception comes from wrong test data (NPE from null field), set non-null values before calling.";
        }

        if (hasUnnecessaryStubbing) {
            return "The test failed with UnnecessaryStubbing — a stub set up in @BeforeEach was not used in one or more test methods." + System.lineSeparator()
                    + "MANDATORY FIXES:" + System.lineSeparator()
                    + "- With @ExtendWith(MockitoExtension.class) (strict mode), every stub must be used by the test that runs it." + System.lineSeparator()
                    + "- SOLUTION A (preferred): Move the stubs OUT of @BeforeEach and INTO the specific @Test method that uses them." + System.lineSeparator()
                    + "  Each test should set up only the stubs it actually needs, nothing more." + System.lineSeparator()
                    + "- SOLUTION B: Remove @BeforeEach entirely. Make every @Test method fully self-contained." + System.lineSeparator()
                    + "- SOLUTION C: Replace `when(mock.x()).thenReturn(v)` in @BeforeEach with `lenient().when(mock.x()).thenReturn(v)`." + System.lineSeparator()
                    + "- Do NOT keep @BeforeEach stubs that are only needed by one test — they cause UnnecessaryStubbing in all other tests.";
        }

        if (hasInvalidUseOfMatchers) {
            return "The test threw InvalidUseOfMatchers — you mixed argument matchers with concrete values in the same when() call." + System.lineSeparator()
                    + "MANDATORY FIXES:" + System.lineSeparator()
                    + "- In Mockito, ALL arguments in a when() or verify() call must be EITHER all matchers OR all concrete values." + System.lineSeparator()
                    + "- WRONG: `when(service.foo(concreteValue, any(Type.class))).thenReturn(...)` — mixed!" + System.lineSeparator()
                    + "- CORRECT: `when(service.foo(eq(concreteValue), any(Type.class))).thenReturn(...)` — all matchers" + System.lineSeparator()
                    + "- CORRECT: `when(service.foo(anyString(), any(Type.class))).thenReturn(...)` — all matchers" + System.lineSeparator()
                    + "- Use `eq(value)` to match a concrete value when another argument uses a matcher like `any()`." + System.lineSeparator()
                    + "- Scan all when() and verify() calls and fix any that mix concrete values with matchers.";
        }

        if (hasPrivateAccess) {
            return "The test called a private or protected method — this is illegal from outside the class." + System.lineSeparator()
                    + "MANDATORY FIXES:" + System.lineSeparator()
                    + "- NEVER call private or protected methods directly from the test." + System.lineSeparator()
                    + "- Only call methods with `public` visibility." + System.lineSeparator()
                    + "- Look at METHODS TO TEST — those are the ONLY methods you should call on `subject`." + System.lineSeparator()
                    + "- If a private method is exercised by a public method, test it indirectly through the public one." + System.lineSeparator()
                    + "- Remove any test that directly calls a private/protected method and replace it with a test" + System.lineSeparator()
                    + "  that calls the public method which internally delegates to the private one.";
        }

        if (hasCheckedExceptionStub) {
            return "The test threw a checked exception from a mock that doesn't declare it — this is invalid in Mockito." + System.lineSeparator()
                    + "MANDATORY FIXES:" + System.lineSeparator()
                    + "- Use `new RuntimeException(\"message\")` (unchecked) in all thenThrow() and doThrow() calls" + System.lineSeparator()
                    + "  UNLESS the mocked method's signature explicitly declares `throws SomeCheckedException`." + System.lineSeparator()
                    + "- Example: instead of `when(service.foo()).thenThrow(new Exception())` use" + System.lineSeparator()
                    + "  `when(service.foo()).thenThrow(new RuntimeException())`." + System.lineSeparator()
                    + "- Catching `Exception` in the method under test also catches `RuntimeException`," + System.lineSeparator()
                    + "  so the test behaviour is preserved.";
        }

        if (hasStatusCodeMismatch) {
            return "The test asserted an HTTP status that the method body cannot produce." + System.lineSeparator()
                    + "MANDATORY FIXES:" + System.lineSeparator()
                    + "- Re-read the method body carefully in METHODS TO TEST." + System.lineSeparator()
                    + "- If the method always returns the SAME status (e.g. `return new ResponseEntity<>(x, HttpStatus.OK)`)," + System.lineSeparator()
                    + "  your assertion MUST expect that exact status — not 401, 403, or 500 if the code never returns them." + System.lineSeparator()
                    + "- Only add tests for status codes that the code explicitly produces (via conditional branches)." + System.lineSeparator()
                    + "- If the method has no branching and always returns 200 OK, only write one test that asserts 200 OK.";
        }

        if (hasMissingImports) {
            return "Orientações obrigatórias para corrigir imports faltantes:" + System.lineSeparator()
                    + "- adicione import explícito para todo tipo usado no teste" + System.lineSeparator()
                    + "- use estes imports reais da classe alvo como base: " + prompt.analysis().importedTypes() + System.lineSeparator()
                    + "- se usar entidades ou repositories do projeto, importe-os explicitamente" + System.lineSeparator()
                    + "- não deixe tipos como AutorRepository, LivroRepository, Autor, Livro, Login, Usuario, Optional, AuthenticationManager ou JwtServiceGenerator sem import";
        }

        if (!hasCannotFindSymbol) {
            return "Ajuste o teste preservando nomes reais, imports corretos e compatibilidade com o código da classe alvo.";
        }

        return "Mandatory fixes for cannot find symbol / method cannot be applied:" + System.lineSeparator()
                + "- Use ONLY real classes and collaborators visible in the source class above." + System.lineSeparator()
                + "- Use the real imports from structural analysis: " + prompt.analysis().importedTypes() + System.lineSeparator()
                + "- Use the real dependency fields: " + prompt.analysis().fieldDependencies() + System.lineSeparator()
                + "- Use the identified constructor: " + prompt.analysis().constructorSignature() + System.lineSeparator()
                + "- Do NOT invent names — use exact names from METHODS TO TEST and imports above." + System.lineSeparator()
                + "- Do NOT access static constants of the tested class (e.g. `ClassName.SECRET` or `ClassName.KEY`)" + System.lineSeparator()
                + "  unless you can see that constant in the SOURCE CLASS section with its exact name." + System.lineSeparator()
                + "  Instead, use the public API: generate test tokens with `subject.generateToken(user)`, not Jwts.builder()." + System.lineSeparator()
                + "- If an import is missing, import the real type — never create a different name.";
    }
}
