package com.keyloop.scheduler.customer.controller;

import jakarta.validation.constraints.Pattern;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.keyloop.scheduler.customer.dto.response.CustomerMatchResponse;
import com.keyloop.scheduler.customer.service.CustomerSearchService;
import com.keyloop.scheduler.web.dto.ItemsResponse;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/customers")
class CustomerController {

    private final CustomerSearchService search;

    CustomerController(CustomerSearchService search) {
        this.search = search;
    }

    @GetMapping
    ItemsResponse<CustomerMatchResponse> search(@RequestParam
            @Pattern(regexp = "\\s*\\S.{0,98}\\S\\s*", message = "must have 2 to 100 characters")
            @Parameter(description = "Part of a name or email, without accents if you like", example = "le minh duc")
            String q) {
        return new ItemsResponse<>(this.search.search(q));
    }
}
