package com.darshan.ledger.common.web;

import java.util.List;
import org.springframework.data.domain.Page;

// A stable page shape for the API. Spring Data's Page serialises with internal details that
// change between versions, so only these fields leave the application.
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages());
    }
}
