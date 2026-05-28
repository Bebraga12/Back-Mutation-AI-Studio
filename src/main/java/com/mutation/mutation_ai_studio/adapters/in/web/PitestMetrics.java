package com.mutation.mutation_ai_studio.adapters.in.web;

record PitestMetrics(
        int mutationScore,
        int total,
        int killed,
        int survivorCount,
        int noCoverage,
        int survivorRate,
        int killedRate,
        String reportFile
) {
}
