package com.keyloop.scheduler;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

public final class DatabaseFixtures {

    public static final String FINGERPRINT = "0123456789abcdef".repeat(4);

    private final JdbcClient jdbc;

    private int sequence;

    public DatabaseFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long dealership(String name) {
        return dealership(name, "Europe/London", LocalTime.of(8, 0), LocalTime.of(18, 0));
    }

    public long dealership(String name, String timeZone, LocalTime opens, LocalTime closes) {
        return jdbc.sql("""
                INSERT INTO dealership (name, time_zone, opens, closes)
                VALUES (:name, :timeZone, :opens, :closes) RETURNING id""")
                .param("name", name)
                .param("timeZone", timeZone)
                .param("opens", opens)
                .param("closes", closes)
                .query(Long.class)
                .single();
    }

    public long serviceType(String code, int durationMinutes) {
        return jdbc.sql("""
                INSERT INTO service_type (code, name, duration_minutes)
                VALUES (:code, :name, :duration) RETURNING id""")
                .param("code", code)
                .param("name", "Service " + code)
                .param("duration", durationMinutes)
                .query(Long.class)
                .single();
    }

    public long bay(long dealershipId, String name) {
        return bay(dealershipId, name, true);
    }

    public long bay(long dealershipId, String name, boolean active) {
        return jdbc.sql("INSERT INTO service_bay (dealership_id, name, active) VALUES (:dealership, :name, :active) RETURNING id")
                .param("dealership", dealershipId)
                .param("name", name)
                .param("active", active)
                .query(Long.class)
                .single();
    }

    public long technician(long dealershipId, String name) {
        return technician(dealershipId, name, true);
    }

    public long technician(long dealershipId, String name, boolean active) {
        return jdbc.sql("INSERT INTO technician (dealership_id, name, active) VALUES (:dealership, :name, :active) RETURNING id")
                .param("dealership", dealershipId)
                .param("name", name)
                .param("active", active)
                .query(Long.class)
                .single();
    }

    public void skill(long technicianId, long serviceTypeId) {
        skill(technicianId, serviceTypeId, true);
    }

    public void skill(long technicianId, long serviceTypeId, boolean active) {
        jdbc.sql("INSERT INTO technician_skill (technician_id, service_type_id, active) VALUES (:technician, :serviceType, :active)")
                .param("technician", technicianId)
                .param("serviceType", serviceTypeId)
                .param("active", active)
                .update();
    }

    public long customer(String name) {
        return jdbc.sql("INSERT INTO customer (name, email) VALUES (:name, :email) RETURNING id")
                .param("name", name)
                .param("email", "customer" + nextSequence() + "@example.com")
                .query(Long.class)
                .single();
    }

    public long vehicle(long customerId) {
        return vehicle(customerId, "TSTVHCL%010d".formatted(nextSequence()));
    }

    public long vehicle(long customerId, String vin) {
        return vehicle(customerId, vin, "Volkswagen", "Golf", 2020);
    }

    public long vehicle(long customerId, String vin, String make, String model, int modelYear) {
        return jdbc.sql("""
                INSERT INTO vehicle (customer_id, vin, make, model, model_year)
                VALUES (:customer, :vin, :make, :model, :modelYear) RETURNING id""")
                .param("customer", customerId)
                .param("vin", vin)
                .param("make", make)
                .param("model", model)
                .param("modelYear", modelYear)
                .query(Long.class)
                .single();
    }

    public UUID appointment(NewAppointment appointment) {
        return insertAppointment(jdbc, appointment, UUID.randomUUID().toString());
    }

    public UUID appointment(NewAppointment appointment, String idempotencyKey) {
        return insertAppointment(jdbc, appointment, idempotencyKey);
    }

    public static UUID insertAppointment(JdbcClient client, NewAppointment appointment, String idempotencyKey) {
        return client.sql("""
                INSERT INTO appointment (dealership_id, customer_id, vehicle_id, service_type_id, service_bay_id,
                                         technician_id, start_at, end_at, status, idempotency_key, request_fingerprint)
                VALUES (:dealership, :customer, :vehicle, :serviceType, :bay,
                        :technician, :startAt, :endAt, :status, :idempotencyKey, :fingerprint)
                RETURNING id""")
                .param("dealership", appointment.dealershipId())
                .param("customer", appointment.customerId())
                .param("vehicle", appointment.vehicleId())
                .param("serviceType", appointment.serviceTypeId())
                .param("bay", appointment.serviceBayId())
                .param("technician", appointment.technicianId())
                .param("startAt", appointment.startAt())
                .param("endAt", appointment.endAt())
                .param("status", appointment.status())
                .param("idempotencyKey", idempotencyKey)
                .param("fingerprint", appointment.requestFingerprint())
                .query(UUID.class)
                .single();
    }

    public Workshop workshop(String name, String timeZone, LocalTime opens, LocalTime closes, int durationMinutes,
            int bays, int technicians) {
        long dealershipId = dealership(name, timeZone, opens, closes);
        long serviceTypeId = serviceType("SERVICE_" + nextSequence(), durationMinutes);
        List<Long> bayIds = new ArrayList<>();
        for (int i = 1; i <= bays; i++) {
            bayIds.add(bay(dealershipId, "Bay " + i));
        }
        List<Long> technicianIds = new ArrayList<>();
        for (int i = 1; i <= technicians; i++) {
            long technicianId = technician(dealershipId, "Technician " + i);
            skill(technicianId, serviceTypeId);
            technicianIds.add(technicianId);
        }
        return new Workshop(dealershipId, serviceTypeId, List.copyOf(bayIds), List.copyOf(technicianIds));
    }

    public static Map<String, Object> bookingRequest(long dealershipId, long customerId, long vehicleId,
            long serviceTypeId, String startTime) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dealershipId", dealershipId);
        body.put("customerId", customerId);
        body.put("vehicleId", vehicleId);
        body.put("serviceTypeId", serviceTypeId);
        body.put("startTime", startTime);
        return body;
    }

    private int nextSequence() {
        return ++sequence;
    }

    public record Workshop(long dealershipId, long serviceTypeId, List<Long> bayIds, List<Long> technicianIds) {

        public long bay(int number) {
            return bayIds.get(number - 1);
        }

        public long technician(int number) {
            return technicianIds.get(number - 1);
        }

        public NewAppointment appointment(long customerId, long vehicleId, int bayNumber, int technicianNumber,
                OffsetDateTime startAt, OffsetDateTime endAt) {
            return NewAppointment.confirmed(dealershipId, customerId, vehicleId, serviceTypeId, bay(bayNumber),
                    technician(technicianNumber), startAt, endAt);
        }

        public Map<String, Object> request(long customerId, long vehicleId, String startTime) {
            return bookingRequest(dealershipId, customerId, vehicleId, serviceTypeId, startTime);
        }
    }

    public record NewAppointment(long dealershipId, long customerId, long vehicleId, long serviceTypeId,
            long serviceBayId, long technicianId, OffsetDateTime startAt, OffsetDateTime endAt, String status,
            String requestFingerprint) {

        public static NewAppointment confirmed(long dealershipId, long customerId, long vehicleId,
                long serviceTypeId, long serviceBayId, long technicianId, OffsetDateTime startAt,
                OffsetDateTime endAt) {
            return new NewAppointment(dealershipId, customerId, vehicleId, serviceTypeId, serviceBayId,
                    technicianId, startAt, endAt, "CONFIRMED", FINGERPRINT);
        }

        public NewAppointment at(OffsetDateTime newStartAt, OffsetDateTime newEndAt) {
            return new NewAppointment(dealershipId, customerId, vehicleId, serviceTypeId, serviceBayId,
                    technicianId, newStartAt, newEndAt, status, requestFingerprint);
        }

        public NewAppointment withBay(long newServiceBayId) {
            return new NewAppointment(dealershipId, customerId, vehicleId, serviceTypeId, newServiceBayId,
                    technicianId, startAt, endAt, status, requestFingerprint);
        }

        public NewAppointment withTechnician(long newTechnicianId) {
            return new NewAppointment(dealershipId, customerId, vehicleId, serviceTypeId, serviceBayId,
                    newTechnicianId, startAt, endAt, status, requestFingerprint);
        }

        public NewAppointment withVehicle(long newCustomerId, long newVehicleId) {
            return new NewAppointment(dealershipId, newCustomerId, newVehicleId, serviceTypeId, serviceBayId,
                    technicianId, startAt, endAt, status, requestFingerprint);
        }

        public NewAppointment withServiceType(long newServiceTypeId) {
            return new NewAppointment(dealershipId, customerId, vehicleId, newServiceTypeId, serviceBayId,
                    technicianId, startAt, endAt, status, requestFingerprint);
        }

        public NewAppointment withStatus(String newStatus) {
            return new NewAppointment(dealershipId, customerId, vehicleId, serviceTypeId, serviceBayId,
                    technicianId, startAt, endAt, newStatus, requestFingerprint);
        }

        public NewAppointment withRequestFingerprint(String newRequestFingerprint) {
            return new NewAppointment(dealershipId, customerId, vehicleId, serviceTypeId, serviceBayId,
                    technicianId, startAt, endAt, status, newRequestFingerprint);
        }
    }
}
