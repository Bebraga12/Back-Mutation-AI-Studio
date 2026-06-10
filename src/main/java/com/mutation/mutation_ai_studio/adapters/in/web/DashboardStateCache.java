package com.mutation.mutation_ai_studio.adapters.in.web;

import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiDiffSnapshot;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiGaugeMetric;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiInsightFeedback;

import java.util.List;

record DashboardStateCache(
        List<ApiGaugeMetric> gaugeMetrics,
        List<ApiInsightFeedback> insights,
        ApiDiffSnapshot diffSnapshot,
        Long durationMs,
        String updatedAt
) {
}
