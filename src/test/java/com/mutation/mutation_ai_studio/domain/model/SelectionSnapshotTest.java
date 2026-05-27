package com.mutation.mutation_ai_studio.domain.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import static org.mockito.Mockito.*;
import java.util.Optional;
import java.util.ArrayList;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(value = MockitoExtension.class)
public class SelectionSnapshotTest {

    private SelectionSnapshot selectionSnapshot;

    @BeforeEach
    public void setUp() {
        selectionSnapshot = new SelectionSnapshot("projectRoot", Instant.now(), 10, Collections.emptyList());
    }

    @Test
    public void testGetProjectRoot() {
        assertEquals("projectRoot", selectionSnapshot.projectRoot());
    }

    @Test
    public void testGetSelectedAt() {
        Instant selectedAt = selectionSnapshot.selectedAt();
        assertNotNull(selectedAt);
    }

    @Test
    public void testGetTotalSelected() {
        int totalSelected = selectionSnapshot.totalSelected();
        assertEquals(10, totalSelected);
    }

    @Test
    public void testGetClasses() {
        List<JavaClassCandidate> classes = selectionSnapshot.classes();
        assertNotNull(classes);
        assertTrue(classes.isEmpty());
    }
}
