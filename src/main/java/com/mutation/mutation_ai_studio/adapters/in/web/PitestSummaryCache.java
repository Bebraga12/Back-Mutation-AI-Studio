package com.mutation.mutation_ai_studio.adapters.in.web;

record PitestSummaryCache(
        int mutationScore,
        int totalMutants,
        int killedMutants,
        int survivingMutants,
        int noCoverageMutants,
        int survivorRate,
        int killedRate,
        String reportFile,
        Long durationMs,
        String updatedAt
) {
}
