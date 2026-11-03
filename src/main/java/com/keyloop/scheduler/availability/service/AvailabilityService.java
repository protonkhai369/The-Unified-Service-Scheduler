package com.keyloop.scheduler.availability.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.keyloop.scheduler.appointment.repository.AppointmentRepository;
import com.keyloop.scheduler.appointment.repository.BookedSlot;
import com.keyloop.scheduler.availability.dto.response.AvailabilityResponse;
import com.keyloop.scheduler.catalog.entity.Dealership;
import com.keyloop.scheduler.catalog.entity.ServiceBay;
import com.keyloop.scheduler.catalog.entity.ServiceType;
import com.keyloop.scheduler.catalog.entity.Technician;
import com.keyloop.scheduler.catalog.repository.DealershipRepository;
import com.keyloop.scheduler.catalog.repository.ServiceBayRepository;
import com.keyloop.scheduler.catalog.repository.ServiceTypeRepository;
import com.keyloop.scheduler.catalog.repository.TechnicianRepository;
import com.keyloop.scheduler.config.SchedulerProperties;
import com.keyloop.scheduler.scheduling.BusinessDay;
import com.keyloop.scheduler.scheduling.Commitment;
import com.keyloop.scheduler.scheduling.SlotFinder;
import com.keyloop.scheduler.web.error.ApiException;
import com.keyloop.scheduler.web.error.ErrorCode;

@Service
@Transactional(readOnly = true)
public class AvailabilityService {

    private final DealershipRepository dealerships;

    private final ServiceTypeRepository serviceTypes;

    private final ServiceBayRepository serviceBays;

    private final TechnicianRepository technicians;

    private final AppointmentRepository appointments;

    private final SchedulerProperties properties;

    private final Clock clock;

    AvailabilityService(DealershipRepository dealerships, ServiceTypeRepository serviceTypes,
            ServiceBayRepository serviceBays, TechnicianRepository technicians, AppointmentRepository appointments,
            SchedulerProperties properties, Clock clock) {
        this.dealerships = dealerships;
        this.serviceTypes = serviceTypes;
        this.serviceBays = serviceBays;
        this.technicians = technicians;
        this.appointments = appointments;
        this.properties = properties;
        this.clock = clock;
    }

    public AvailabilityResponse availability(long dealershipId, long serviceTypeId, LocalDate date) {
        Dealership dealership = this.dealerships.findById(dealershipId)
                .orElseThrow(() -> new ApiException(ErrorCode.DEALERSHIP_NOT_FOUND, dealershipId));
        ServiceType serviceType = this.serviceTypes.findById(serviceTypeId)
                .orElseThrow(() -> new ApiException(ErrorCode.SERVICE_TYPE_NOT_FOUND, serviceTypeId));
        BusinessDay day = new BusinessDay(date, ZoneId.of(dealership.getTimeZone()), dealership.getOpens(),
                dealership.getCloses());
        Instant now = this.clock.instant();
        if (!day.openingHours().end().isAfter(now)) {
            return AvailabilityResponse.of(dealership, serviceType, date, List.of());
        }

        List<Long> bayIds = this.serviceBays.findByDealershipIdAndActiveTrueOrderById(dealershipId).stream()
                .map(ServiceBay::getId)
                .toList();
        List<Long> technicianIds = this.technicians.findActiveQualified(dealershipId, serviceTypeId).stream()
                .map(Technician::getId)
                .toList();
        List<Commitment> commitments = this.appointments
                .findConfirmedStartingBetween(dealershipId, day.wholeDay().start(), day.wholeDay().end())
                .stream()
                .map(BookedSlot::toCommitment)
                .toList();
        return AvailabilityResponse.of(dealership, serviceType, date,
                SlotFinder.find(day, Duration.ofMinutes(serviceType.getDurationMinutes()), this.properties.slotStep(),
                        now, bayIds, technicianIds, commitments));
    }
}
