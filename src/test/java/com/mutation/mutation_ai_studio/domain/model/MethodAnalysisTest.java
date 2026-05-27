package com.mutation.mutation_ai_studio.domain.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.util.Arrays;
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
public class MethodAnalysisTest {

    private MethodAnalysis methodAnalysis;

    @BeforeEach
    public void setUp() {
        methodAnalysis = new MethodAnalysis("testMethod", "void", Arrays.asList("param1", "param2"), Arrays.asList("Exception1"), "methodBody");
    }

    @Test
    public void testGetMethodName() {
        assertEquals("testMethod", methodAnalysis.methodName());
    }

    @Test
    public void testGetReturnType() {
        assertEquals("void", methodAnalysis.returnType());
    }

    @Test
    public void testGetParameters() {
        List<String> expectedParameters = Arrays.asList("param1", "param2");
        assertEquals(expectedParameters, methodAnalysis.parameters());
    }

    @Test
    public void testGetThrownExceptions() {
        List<String> expectedExceptions = Arrays.asList("Exception1");
        assertEquals(expectedExceptions, methodAnalysis.thrownExceptions());
    }

    @Test
    public void testGetMethodBody() {
        assertEquals("methodBody", methodAnalysis.methodBody());
    }
}
