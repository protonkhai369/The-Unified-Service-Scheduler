package com.keyloop.scheduler.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;

import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

import com.keyloop.scheduler.DatabaseIntegrationTest;

/** Dealership-catalog spec (dealerships and their resources) and api-conventions, over HTTP on fixture data. */
class DealershipIntegrationTest extends DatabaseIntegrationTest {

    private static final ParameterizedTypeReference<List<Long>> IDS = new ParameterizedTypeReference<>() {
    };

    @Test
    void returnsAnEmptyCollectionAsEmptyItems() {
        http.get().uri("/api/v1/dealerships")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$.items").isArray()
                .jsonPath("$.items").isEmpty();
    }

    @Test
    void listsDealershipsWithTheirZoneAndOpeningHours() {
        long london = fixtures.dealership("Riverside Motors London");
        long saigon = fixtures.dealership("Saigon Auto Centre", "Asia/Ho_Chi_Minh", LocalTime.of(7, 30),
                LocalTime.of(17, 30));

        http.get().uri("/api/v1/dealerships")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[*].id").value(IDS, ids -> assertThat(ids).containsExactly(london, saigon))
                .jsonPath("$.items[0].opens").isEqualTo("08:00")
                .jsonPath("$.items[0].closes").isEqualTo("18:00")
                .jsonPath("$.items[1].name").isEqualTo("Saigon Auto Centre")
                .jsonPath("$.items[1].timeZone").isEqualTo("Asia/Ho_Chi_Minh")
                .jsonPath("$.items[1].opens").isEqualTo("07:30")
                .jsonPath("$.items[1].closes").isEqualTo("17:30");
    }

    @Test
    void returnsDealershipWithItsOwnBaysAndTechniciansIncludingInactiveOnes() {
        long dealership = fixtures.dealership("Riverside Motors London");
        long other = fixtures.dealership("Other Motors");
        long bay1 = fixtures.bay(dealership, "Bay 1");
        fixtures.bay(other, "Other bay");
        long closedBay = fixtures.bay(dealership, "Bay 2", false);
        long alice = fixtures.technician(dealership, "Alice Morgan");
        fixtures.technician(other, "Other technician");
        long chloe = fixtures.technician(dealership, "Chloe Evans", false);

        http.get().uri("/api/v1/dealerships/{id}", dealership)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.id").isEqualTo(dealership)
                .jsonPath("$.name").isEqualTo("Riverside Motors London")
                .jsonPath("$.timeZone").isEqualTo("Europe/London")
                .jsonPath("$.opens").isEqualTo("08:00")
                .jsonPath("$.closes").isEqualTo("18:00")
                .jsonPath("$.serviceBays[*].id").value(IDS, ids -> assertThat(ids).containsExactly(bay1, closedBay))
                .jsonPath("$.serviceBays[0].name").isEqualTo("Bay 1")
                .jsonPath("$.serviceBays[0].active").isEqualTo(true)
                .jsonPath("$.serviceBays[1].active").isEqualTo(false)
                .jsonPath("$.technicians[*].id").value(IDS, ids -> assertThat(ids).containsExactly(alice, chloe))
                .jsonPath("$.technicians[0].name").isEqualTo("Alice Morgan")
                .jsonPath("$.technicians[0].active").isEqualTo(true)
                .jsonPath("$.technicians[1].active").isEqualTo(false);
    }

    @Test
    void listsOnlyActiveQualificationsInAscendingOrder() {
        long dealership = fixtures.dealership("Riverside Motors London");
        long oilChange = fixtures.serviceType("OIL_CHANGE", 45);
        long brakeService = fixtures.serviceType("BRAKE_SERVICE", 90);
        long fullService = fixtures.serviceType("FULL_SERVICE", 180);
        long alice = fixtures.technician(dealership, "Alice Morgan");
        long ben = fixtures.technician(dealership, "Ben Carter");
        fixtures.skill(alice, fullService);
        fixtures.skill(alice, oilChange);
        fixtures.skill(alice, brakeService, false);

        http.get().uri("/api/v1/dealerships/{id}", dealership)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.technicians[0].id").isEqualTo(alice)
                .jsonPath("$.technicians[0].qualifiedServiceTypeIds").value(IDS, ids ->
                        assertThat(ids).containsExactly(oilChange, fullService))
                .jsonPath("$.technicians[1].id").isEqualTo(ben)
                .jsonPath("$.technicians[1].qualifiedServiceTypeIds").isArray()
                .jsonPath("$.technicians[1].qualifiedServiceTypeIds").isEmpty();
    }

    @Test
    void unknownDealershipIsNotFoundProblem() {
        http.get().uri("/api/v1/dealerships/999")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Not Found")
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.detail").isEqualTo("Dealership 999 does not exist.")
                .jsonPath("$.instance").isEqualTo("/api/v1/dealerships/999")
                .jsonPath("$.code").isEqualTo("DEALERSHIP_NOT_FOUND")
                .jsonPath("$.requestId").isNotEmpty();
    }

    @Test
    void nonNumericDealershipIdIsValidationProblem() {
        http.get().uri("/api/v1/dealerships/abc")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.errors[*].field").value((List<String> fields) -> assertThat(fields).containsExactly("id"));
    }

    @Test
    void nonPositiveDealershipIdIsValidationProblem() {
        http.get().uri("/api/v1/dealerships/0")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.errors[*].field").value((List<String> fields) -> assertThat(fields).containsExactly("id"));
    }
}
