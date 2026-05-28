package com.mutation.mutation_ai_studio.adapters.in.web.dto;

import java.util.List;

public record ApiDashboardData(
        List<ApiGaugeMetric> gaugeMetrics,
        List<ApiInsightFeedback> insights,
        ApiDiffSnapshot diffSnapshot
) {
}
