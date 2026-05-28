package com.mutation.mutation_ai_studio.adapters.in.web.dto;

import java.util.List;

public record ApiItemsResponse<T>(
        List<T> items
) {
}
