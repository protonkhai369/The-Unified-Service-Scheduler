package com.keyloop.scheduler.account.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.keyloop.scheduler.account.dto.response.AccountResponse;
import com.keyloop.scheduler.account.dto.response.VehicleResponse;
import com.keyloop.scheduler.account.service.AccountService;
import com.keyloop.scheduler.security.AuthenticatedUser;
import com.keyloop.scheduler.web.dto.ItemsResponse;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/me")
class AccountController {

    private final AccountService accounts;

    AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    AccountResponse me(@AuthenticationPrincipal AuthenticatedUser caller) {
        return this.accounts.account(caller);
    }

    @GetMapping("/vehicles")
    ItemsResponse<VehicleResponse> vehicles(@AuthenticationPrincipal AuthenticatedUser caller) {
        return new ItemsResponse<>(this.accounts.vehicles(caller));
    }
}
