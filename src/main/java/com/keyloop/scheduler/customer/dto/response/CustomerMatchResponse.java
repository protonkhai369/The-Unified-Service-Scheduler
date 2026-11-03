package com.keyloop.scheduler.customer.dto.response;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.keyloop.scheduler.account.dto.response.VehicleResponse;
import com.keyloop.scheduler.customer.entity.Customer;

public record CustomerMatchResponse(long id, String name, String email, @Nullable String phone,
        List<VehicleResponse> vehicles) {

    public static CustomerMatchResponse of(Customer customer, List<VehicleResponse> vehicles) {
        return new CustomerMatchResponse(customer.getId(), customer.getName(), customer.getEmail(), customer.getPhone(),
                vehicles);
    }
}
