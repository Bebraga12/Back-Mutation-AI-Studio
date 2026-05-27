package com.mutation.mutation_ai_studio.domain.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Optional;
import java.util.ArrayList;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(value = MockitoExtension.class)
public class ClassTestPromptTest {

    private ClassTestPrompt classTestPrompt;

    @BeforeEach
    public void setUp() {
        classTestPrompt = new ClassTestPrompt("TestClass", "com.example.TestClass", "src/main/java/com/example/TestClass.java", Collections.emptyList(), null, "public class TestClass {}", "Generate a JUnit 5 + Mockito unit test file for the Java class below.", Path.of("path/to/saved/file"));
    }

    @Test
    public void testGetClassName() {
        assertEquals("TestClass", classTestPrompt.className());
    }

    @Test
    public void testGetFullyQualifiedName() {
        assertEquals("com.example.TestClass", classTestPrompt.fullyQualifiedName());
    }

    @Test
    public void testGetRelativePath() {
        assertEquals("src/main/java/com/example/TestClass.java", classTestPrompt.relativePath());
    }

    @Test
    public void testGetDependencies() {
        assertTrue(classTestPrompt.dependencies().isEmpty());
    }

    @Test
    public void testGetAnalysis() {
        assertNull(classTestPrompt.analysis());
    }

    @Test
    public void testGetSourceCode() {
        assertEquals("public class TestClass {}", classTestPrompt.sourceCode());
    }

    @Test
    public void testGetPrompt() {
        assertEquals("Generate a JUnit 5 + Mockito unit test file for the Java class below.", classTestPrompt.prompt());
    }

    @Test
    public void testGetSavedPath() {
        assertEquals(Path.of("path/to/saved/file"), classTestPrompt.savedPath());
    }
}
