package com.keyloop.scheduler.catalog.controller;

import jakarta.validation.constraints.Positive;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.keyloop.scheduler.catalog.dto.response.DealershipResponse;
import com.keyloop.scheduler.catalog.dto.response.DealershipSummaryResponse;
import com.keyloop.scheduler.catalog.service.DealershipService;
import com.keyloop.scheduler.web.dto.ItemsResponse;

/**
 * Read-only dealerships, so clients can discover the ids, opening hours and resources a booking
 * depends on. Not annotated with {@code @Validated}: parameter constraints such as {@code @Positive}
 * are checked by Spring MVC's built-in method validation, which the exception handler reports as
 * {@code VALIDATION_FAILED}.
 */
@RestController
@RequestMapping("/api/v1/dealerships")
class DealershipController {

    private final DealershipService dealerships;

    DealershipController(DealershipService dealerships) {
        this.dealerships = dealerships;
    }

    @GetMapping
    ItemsResponse<DealershipSummaryResponse> dealerships() {
        return new ItemsResponse<>(this.dealerships.dealerships());
    }

    @GetMapping("/{id}")
    DealershipResponse dealership(@PathVariable @Positive long id) {
        return this.dealerships.dealership(id);
    }
}
