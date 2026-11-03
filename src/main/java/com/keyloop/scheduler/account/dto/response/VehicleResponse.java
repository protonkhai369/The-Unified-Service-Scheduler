package com.keyloop.scheduler.account.dto.response;

import org.jspecify.annotations.Nullable;

import com.keyloop.scheduler.customer.entity.Vehicle;

public record VehicleResponse(long id, String vin, String make, String model, @Nullable Integer modelYear) {

    public static VehicleResponse of(Vehicle vehicle) {
        return new VehicleResponse(vehicle.getId(), vehicle.getVin(), vehicle.getMake(), vehicle.getModel(),
                vehicle.getModelYear());
    }
}
