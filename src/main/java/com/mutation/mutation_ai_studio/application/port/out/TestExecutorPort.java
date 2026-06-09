package com.mutation.mutation_ai_studio.application.port.out;

import com.mutation.mutation_ai_studio.domain.model.TestExecutionFeedback;

import java.nio.file.Path;

public interface TestExecutorPort {

    TestExecutionFeedback compile(Path projectRoot, String testClassName);

    TestExecutionFeedback execute(Path projectRoot, String testClassName);
}
