package com.keyloop.scheduler.account.dto.response;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.keyloop.scheduler.appointment.dto.response.ResourceRef;
import com.keyloop.scheduler.security.Role;

public record AccountResponse(UUID userId, @Nullable Role role, @Nullable ResourceRef customer,
        @Nullable ResourceRef dealership) {
}
