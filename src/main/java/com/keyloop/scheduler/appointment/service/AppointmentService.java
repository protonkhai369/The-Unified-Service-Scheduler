package com.keyloop.scheduler.appointment.service;

import java.time.Clock;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.keyloop.scheduler.appointment.dto.response.AppointmentResponse;
import com.keyloop.scheduler.appointment.entity.AppointmentStatus;
import com.keyloop.scheduler.appointment.repository.AppointmentRepository;
import com.keyloop.scheduler.appointment.repository.AppointmentView;
import com.keyloop.scheduler.web.error.ApiException;
import com.keyloop.scheduler.web.error.ErrorCode;

/** Reads and cancels appointments. Booking lives in {@link BookingService}. */
@Service
public class AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentService.class);

    private final AppointmentRepository appointments;

    private final Clock clock;

    AppointmentService(AppointmentRepository appointments, Clock clock) {
        this.appointments = appointments;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AppointmentResponse appointment(UUID id) {
        return AppointmentResponse.of(view(id));
    }

    /**
     * One conditional update decides, so concurrent cancellations cannot both change the row: the
     * second waits for the first's row lock, re-checks the condition on the committed row and changes
     * nothing. Zero rows changed then means unknown, already cancelled (fine) or already started.
     * Does not take the booking lock: cancelling only frees resources.
     */
    @Transactional
    public AppointmentResponse cancel(UUID id) {
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

    private AppointmentView view(UUID id) {
        return this.appointments.findView(id).orElseThrow(() -> new ApiException(ErrorCode.APPOINTMENT_NOT_FOUND, id));
    }
}
