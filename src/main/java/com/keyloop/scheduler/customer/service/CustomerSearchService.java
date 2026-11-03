package com.keyloop.scheduler.customer.service;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.keyloop.scheduler.account.dto.response.VehicleResponse;
import com.keyloop.scheduler.customer.dto.response.CustomerMatchResponse;
import com.keyloop.scheduler.customer.entity.Customer;
import com.keyloop.scheduler.customer.entity.Vehicle;
import com.keyloop.scheduler.customer.repository.CustomerRepository;
import com.keyloop.scheduler.customer.repository.VehicleRepository;

@Service
public class CustomerSearchService {

    private final CustomerRepository customers;

    private final VehicleRepository vehicles;

    CustomerSearchService(CustomerRepository customers, VehicleRepository vehicles) {
        this.customers = customers;
        this.vehicles = vehicles;
    }

    @PreAuthorize("T(com.keyloop.scheduler.security.AccessPolicy).mayFindCustomers(principal)")
    @Transactional(readOnly = true)
    public List<CustomerMatchResponse> search(String query) {
        List<Customer> matches = this.customers.search(query.strip());
        if (matches.isEmpty()) {
            return List.of();
        }
        Map<Long, List<VehicleResponse>> vehiclesByCustomer = this.vehicles
                .findByCustomerIdInOrderById(matches.stream().map(Customer::getId).toList()).stream()
                .collect(groupingBy(Vehicle::getCustomerId, mapping(VehicleResponse::of, toList())));
        return matches.stream()
                .map(customer -> CustomerMatchResponse.of(customer,
                        vehiclesByCustomer.getOrDefault(customer.getId(), List.of())))
                .toList();
    }
}
