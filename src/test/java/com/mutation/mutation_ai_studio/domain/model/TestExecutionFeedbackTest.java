package com.mutation.mutation_ai_studio.domain.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
class TestExecutionFeedbackTest {

    @BeforeEach
    void setUp() {
        // No setup needed for this test class
    }

    @Test
    void testConstructorAndGetters() {
        boolean passed = true;
        int exitCode = 0;
        List<String> errors = List.of("error1", "error2");
        String output = "output";
        TestExecutionFeedback feedback = new TestExecutionFeedback(passed, exitCode, errors, output);
        assertEquals(passed, feedback.passed());
        assertEquals(exitCode, feedback.exitCode());
        assertEquals(errors, feedback.errors());
        assertEquals(output, feedback.output());
    }
}
