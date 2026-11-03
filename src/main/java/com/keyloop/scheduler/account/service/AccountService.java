package com.keyloop.scheduler.account.service;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.keyloop.scheduler.account.dto.response.AccountResponse;
import com.keyloop.scheduler.account.dto.response.VehicleResponse;
import com.keyloop.scheduler.appointment.dto.response.ResourceRef;
import com.keyloop.scheduler.catalog.repository.DealershipRepository;
import com.keyloop.scheduler.customer.repository.CustomerRepository;
import com.keyloop.scheduler.customer.repository.VehicleRepository;
import com.keyloop.scheduler.security.AuthenticatedUser;
import com.keyloop.scheduler.security.Role;

@Service
public class AccountService {

    private final CustomerRepository customers;

    private final DealershipRepository dealerships;

    private final VehicleRepository vehicles;

    AccountService(CustomerRepository customers, DealershipRepository dealerships, VehicleRepository vehicles) {
        this.customers = customers;
        this.dealerships = dealerships;
        this.vehicles = vehicles;
    }

    @Transactional(readOnly = true)
    public AccountResponse account(AuthenticatedUser caller) {
        return new AccountResponse(caller.userId(), caller.role(), customer(caller), dealership(caller));
    }

    @PreAuthorize("T(com.keyloop.scheduler.security.AccessPolicy).isLinkedCustomer(principal)")
    @Transactional(readOnly = true)
    public List<VehicleResponse> vehicles(AuthenticatedUser caller) {
        return this.vehicles.findByCustomerIdOrderById(caller.customerId()).stream().map(VehicleResponse::of).toList();
    }

    private @Nullable ResourceRef customer(AuthenticatedUser caller) {
        if (caller.role() != Role.CUSTOMER || caller.customerId() == null) {
            return null;
        }
        return this.customers.findById(caller.customerId())
                .map(customer -> new ResourceRef(customer.getId(), customer.getName()))
                .orElse(null);
    }

    private @Nullable ResourceRef dealership(AuthenticatedUser caller) {
        if (caller.role() != Role.ADVISOR || caller.dealershipId() == null) {
            return null;
        }
        return this.dealerships.findById(caller.dealershipId())
                .map(dealership -> new ResourceRef(dealership.getId(), dealership.getName()))
                .orElse(null);
    }
}
