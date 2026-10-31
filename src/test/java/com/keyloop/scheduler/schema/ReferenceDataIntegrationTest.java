package com.keyloop.scheduler.schema;

import static com.keyloop.scheduler.SqlAssertions.assertRejected;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.keyloop.scheduler.DatabaseIntegrationTest;

/** Spec: scheduling-data-model, requirement "Reference data integrity". */
class ReferenceDataIntegrationTest extends DatabaseIntegrationTest {

    private static final String VALID_VIN = "WVWZZZ1KZ8W000001";

    @Test
    @DisplayName("Valid reference data is stored")
    void storesValidReferenceData() {
        long dealership = fixtures.dealership("Riverside", "Asia/Ho_Chi_Minh", LocalTime.of(7, 30), LocalTime.of(17, 30));
        long serviceType = fixtures.serviceType("OIL_CHANGE", 45);
        long technician = fixtures.technician(dealership, "Alice");
        fixtures.bay(dealership, "Bay 1");
        fixtures.skill(technician, serviceType);
        long vehicle = fixtures.vehicle(fixtures.customer("Emma"), VALID_VIN);

        assertThat(jdbc.sql("SELECT vin FROM vehicle WHERE id = :id").param("id", vehicle).query(String.class).single())
                .isEqualTo(VALID_VIN);
    }

    @ParameterizedTest(name = "VIN \"{0}\"")
    @DisplayName("A VIN that is not 17 characters of A-H, J-N, P, R-Z, 0-9 is rejected")
    @ValueSource(strings = {
            "WVWZZZ1KZ8W00000I", // letter I
            "WVWZZZ1KZ8W00000O", // letter O
            "WVWZZZ1KZ8W00000Q", // letter Q
            "wvwzzz1kz8w000001", // lower case
            "WVWZZZ1KZ8W00001", // 16 characters
            "WVWZZZ1KZ8W0000001", // 18 characters
            "WVWZZZ1KZ8W 00001" // space
    })
    void rejectsMalformedVin(String vin) {
        long customer = fixtures.customer("Emma");

        assertRejected("23514", "vehicle_vin_format", () -> fixtures.vehicle(customer, vin));
    }

    @Test
    @DisplayName("A second vehicle with an existing VIN is rejected")
    void rejectsDuplicateVin() {
        fixtures.vehicle(fixtures.customer("Emma"), VALID_VIN);
        long otherCustomer = fixtures.customer("Duc");

        assertRejected("23505", "vehicle_vin_unique", () -> fixtures.vehicle(otherCustomer, VALID_VIN));
    }

    @Test
    @DisplayName("A service type of 0 minutes is rejected")
    void rejectsNonPositiveDuration() {
        assertRejected("23514", "service_type_duration_positive", () -> fixtures.serviceType("NOTHING", 0));
    }

    @Test
    @DisplayName("A dealership that opens at 18:00 and closes at 08:00 is rejected")
    void rejectsClosingBeforeOpening() {
        assertRejected("23514", "dealership_hours_order",
                () -> fixtures.dealership("Backwards", "Europe/London", LocalTime.of(18, 0), LocalTime.of(8, 0)));
    }
}
