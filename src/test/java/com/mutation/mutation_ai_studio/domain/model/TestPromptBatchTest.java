package com.mutation.mutation_ai_studio.domain.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import java.util.Optional;
import java.util.ArrayList;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

public class TestPromptBatchTest {

    @Mock
    private List<ClassTestPrompt> prompts;

    private TestPromptBatch testPromptBatch;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        testPromptBatch = new TestPromptBatch("projectRoot", Instant.now(), 10, prompts);
    }

    @Test
    public void testGetProjectRoot() {
        assertEquals("projectRoot", testPromptBatch.projectRoot());
    }

    @Test
    public void testGetCreatedAt() {
        Instant createdAt = testPromptBatch.createdAt();
        assertNotNull(createdAt);
    }

    @Test
    public void testGetTotalSelected() {
        int totalSelected = testPromptBatch.totalSelected();
        assertEquals(10, totalSelected);
    }

    @Test
    public void testGetPrompts() {
        List<ClassTestPrompt> result = testPromptBatch.prompts();
        assertSame(prompts, result);
    }
}
