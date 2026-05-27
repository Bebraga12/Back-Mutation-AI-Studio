package com.mutation.mutation_ai_studio.adapters.in.cli;

import com.mutation.mutation_ai_studio.domain.model.JavaClassCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(value = MockitoExtension.class)
class ScanOutputFormatterTest {

    private ScanOutputFormatter formatter;

    private Path projectRoot;

    private List<JavaClassCandidate> classes;

    @BeforeEach
    void setUp() {
        formatter = new ScanOutputFormatter();
        projectRoot = mock(Path.class);
        classes = new ArrayList<>();
    }

    @Test
    void testPrintEmptyClasses() {
        formatter.print(projectRoot, classes, false, false, null);
        verifyNoMoreInteractions(projectRoot);
    }

    @Test
    void testPrintOnlyCategory() {
        JavaClassCandidate candidate1 = new JavaClassCandidate("ServiceClass", "service", "path/to/service", "service");
        classes.add(candidate1);
        formatter.print(projectRoot, classes, false, false, ScanCategory.SERVICE);
        verifyNoMoreInteractions(projectRoot);
    }

    @Test
    void testPrintDefault() {
        JavaClassCandidate candidate1 = new JavaClassCandidate("ServiceClass", "service", "path/to/service", "service");
        classes.add(candidate1);
        formatter.print(projectRoot, classes, false, false, null);
        verifyNoMoreInteractions(projectRoot);
    }

    @Test
    void testPrintFocusTestable() {
        JavaClassCandidate candidate1 = new JavaClassCandidate("ServiceClass", "service", "path/to/service", "service");
        classes.add(candidate1);
        formatter.print(projectRoot, classes, false, true, null);
        verifyNoMoreInteractions(projectRoot);
    }

    @Test
    void testPrintVerbose() {
        JavaClassCandidate candidate1 = new JavaClassCandidate("ServiceClass", "service", "path/to/service", "service");
        classes.add(candidate1);
        formatter.print(projectRoot, classes, true, false, null);
        verifyNoMoreInteractions(projectRoot);
    }
}
