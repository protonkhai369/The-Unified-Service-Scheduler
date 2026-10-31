package com.keyloop.scheduler.catalog.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.keyloop.scheduler.catalog.entity.ServiceBay;

public interface ServiceBayRepository extends JpaRepository<ServiceBay, Long> {

    List<ServiceBay> findByDealershipIdOrderById(long dealershipId);
}
