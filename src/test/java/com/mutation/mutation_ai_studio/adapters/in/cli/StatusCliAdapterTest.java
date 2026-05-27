package com.mutation.mutation_ai_studio.adapters.in.cli;

import com.mutation.mutation_ai_studio.application.port.in.ReadSelectionStatusUseCase;
import com.mutation.mutation_ai_studio.application.port.out.SelectionRepositoryPort;
import com.mutation.mutation_ai_studio.domain.model.SelectionSnapshot;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class StatusCliAdapterTest {

    @Mock
    private ReadSelectionStatusUseCase readSelectionStatusUseCase;

    @Mock
    private SelectionRepositoryPort selectionRepositoryPort;

    @InjectMocks
    private StatusCliAdapter subject;

    @Test
    void deveInstanciarSubjeto() {
        assertNotNull(subject);
    }
}
