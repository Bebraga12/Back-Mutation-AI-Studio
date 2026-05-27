package com.mutation.mutation_ai_studio.domain.model;

import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(value = MockitoExtension.class)
public class JavaClassCandidateTest {

    @Test
    public void testConstructorAndGetters() {
        String className = "TestClass";
        String packageName = "com.example";
        String fullyQualifiedName = "com.example.TestClass";
        String relativePath = "src/main/java/com/example/TestClass.java";
        JavaClassCandidate candidate = new JavaClassCandidate(className, packageName, fullyQualifiedName, relativePath);
        assertEquals(className, candidate.className());
        assertEquals(packageName, candidate.packageName());
        assertEquals(fullyQualifiedName, candidate.fullyQualifiedName());
        assertEquals(relativePath, candidate.relativePath());
    }
}
