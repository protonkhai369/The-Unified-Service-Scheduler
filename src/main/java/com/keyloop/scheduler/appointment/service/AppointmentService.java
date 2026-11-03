package com.keyloop.scheduler.appointment.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.keyloop.scheduler.appointment.dto.response.AppointmentResponse;
import com.keyloop.scheduler.appointment.entity.Appointment;
import com.keyloop.scheduler.appointment.entity.AppointmentStatus;
import com.keyloop.scheduler.appointment.repository.AppointmentRepository;
import com.keyloop.scheduler.appointment.repository.AppointmentView;
import com.keyloop.scheduler.catalog.entity.Dealership;
import com.keyloop.scheduler.catalog.repository.DealershipRepository;
import com.keyloop.scheduler.scheduling.BusinessDay;
import com.keyloop.scheduler.scheduling.TimeRange;
import com.keyloop.scheduler.security.AccessPolicy;
import com.keyloop.scheduler.security.AuthenticatedUser;
import com.keyloop.scheduler.web.error.ApiException;
import com.keyloop.scheduler.web.error.ErrorCode;

@Service
public class AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentService.class);

    private final AppointmentRepository appointments;

    private final DealershipRepository dealerships;

    private final Clock clock;

    AppointmentService(AppointmentRepository appointments, DealershipRepository dealerships, Clock clock) {
        this.appointments = appointments;
        this.dealerships = dealerships;
        this.clock = clock;
    }

    @PreAuthorize("T(com.keyloop.scheduler.security.AccessPolicy).isLinkedCustomer(principal)")
    @Transactional(readOnly = true)
    public List<AppointmentResponse> appointmentsOf(AuthenticatedUser caller) {
        return this.appointments.findViewsOfCustomer(caller.customerId()).stream().map(AppointmentResponse::of).toList();
    }

    @PreAuthorize("T(com.keyloop.scheduler.security.AccessPolicy).mayViewSchedule(principal, #dealershipId)")
    @Transactional(readOnly = true)
    public List<AppointmentResponse> dealershipDay(long dealershipId, LocalDate date) {
        Dealership dealership = this.dealerships.findById(dealershipId)
                .orElseThrow(() -> new ApiException(ErrorCode.DEALERSHIP_NOT_FOUND, dealershipId));
        TimeRange day = new BusinessDay(date, ZoneId.of(dealership.getTimeZone()), dealership.getOpens(),
                dealership.getCloses()).wholeDay();
        return this.appointments.findViewsStartingBetween(dealershipId, day.start(), day.end()).stream()
                .map(AppointmentResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public AppointmentResponse appointment(UUID id, AuthenticatedUser caller) {
        return AppointmentResponse.of(visibleView(id, caller));
    }

    @Transactional
    public AppointmentResponse cancel(UUID id, AuthenticatedUser caller) {
        visibleView(id, caller);
        int cancelled = this.appointments.cancelIfNotStarted(id, this.clock.instant());
        AppointmentView view = view(id);
        if (cancelled == 1) {
            log.info("Appointment {} cancelled", id);
        }
        else if (view.appointment().getStatus() == AppointmentStatus.CONFIRMED) {
            throw new ApiException(ErrorCode.APPOINTMENT_ALREADY_STARTED, id);
        }
        return AppointmentResponse.of(view);
    }

    private AppointmentView visibleView(UUID id, AuthenticatedUser caller) {
        if (!AccessPolicy.mayUseAppointments(caller)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        AppointmentView view = view(id);
        Appointment appointment = view.appointment();
        if (!AccessPolicy.maySee(caller, appointment.getDealershipId(), appointment.getCustomerId())) {
            throw new ApiException(ErrorCode.APPOINTMENT_NOT_FOUND, id);
        }
        return view;
    }

    private AppointmentView view(UUID id) {
        return this.appointments.findView(id).orElseThrow(() -> new ApiException(ErrorCode.APPOINTMENT_NOT_FOUND, id));
    }
}
