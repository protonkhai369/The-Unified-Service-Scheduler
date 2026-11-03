package com.keyloop.scheduler.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.text.Normalizer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import com.keyloop.scheduler.appointment.AppointmentApiTestSupport;

class CustomerSearchIntegrationTest extends AppointmentApiTestSupport {

    @Nested
    @DisplayName("Staff search customers by name or email")
    class StaffSearch {

        @Test
        @DisplayName("Case and accents are ignored")
        void caseAndAccentsAreIgnored() {
            fixtures.customer("Lê Minh Đức");
            fixtures.customer("Emma Wilson");

            assertThat(names(search(tokens.admin(), "le minh duc"))).containsExactly("Lê Minh Đức");
            assertThat(names(search(tokens.admin(), "LÊ MINH ĐỨC"))).containsExactly("Lê Minh Đức");
        }

        @Test
        @DisplayName("Decomposed input")
        void decomposedInput() {
            fixtures.customer("Lê Minh Đức");

            assertThat(names(search(tokens.admin(), Normalizer.normalize("Đức", Normalizer.Form.NFD))))
                    .containsExactly("Lê Minh Đức");
        }

        @Test
        @DisplayName("Part of a name")
        void partOfAName() {
            fixtures.customer("Lê Minh Đức");
            fixtures.customer("Emma Wilson");

            assertThat(names(search(tokens.admin(), "duc"))).containsExactly("Lê Minh Đức");
            assertThat(names(search(tokens.admin(), "wil"))).containsExactly("Emma Wilson");
        }

        @Test
        @DisplayName("Typing mistake")
        void typingMistake() {
            fixtures.customer("Lê Minh Đức");

            assertThat(names(search(tokens.admin(), "lee minh duk"))).containsExactly("Lê Minh Đức");
        }

        @Test
        @DisplayName("Different word order")
        void differentWordOrder() {
            fixtures.customer("Lê Minh Đức");

            assertThat(names(search(tokens.admin(), "duc le"))).containsExactly("Lê Minh Đức");
        }

        @Test
        @DisplayName("Email")
        void email() {
            fixtures.customer("Lê Minh Đức", "minh.duc@example.com");

            assertThat(names(search(tokens.admin(), "minh.duc@"))).containsExactly("Lê Minh Đức");
        }

        @Test
        @DisplayName("Vehicles come with the customer")
        void vehiclesComeWithTheCustomer() {
            long duc = fixtures.customer("Lê Minh Đức");
            long focus = fixtures.vehicle(duc, "WF0AXXGCDA0000003", "Ford", "Focus", 2018);
            long golf = fixtures.vehicle(duc, "WVWZZZ1KZ8W000001", "Volkswagen", "Golf", 2019);

            Map<String, Object> customer = items(search(tokens.admin(), "duc")).getFirst();

            assertThat(customer).containsEntry("id", (int) duc).containsKeys("email", "phone");
            assertThat(vehicles(customer)).extracting(vehicle -> ((Number) vehicle.get("id")).longValue())
                    .containsExactly(focus, golf);
            assertThat(vehicles(customer).getFirst()).containsEntry("vin", "WF0AXXGCDA0000003")
                    .containsEntry("make", "Ford")
                    .containsEntry("model", "Focus")
                    .containsEntry("modelYear", 2018);
        }

        @Test
        @DisplayName("Containment before similarity")
        void containmentBeforeSimilarity() {
            fixtures.customer("Lee Minh Duk");
            fixtures.customer("Lê Minh Đức");

            assertThat(names(search(tokens.admin(), "le minh duc"))).containsExactly("Lê Minh Đức", "Lee Minh Duk");
        }

        @Test
        @DisplayName("At most twenty results")
        void atMostTwentyResults() {
            IntStream.rangeClosed(1, 25).forEach(i -> fixtures.customer("Nguyen " + i));

            assertThat(items(search(tokens.admin(), "nguyen"))).hasSize(20);
        }

        @Test
        @DisplayName("No match")
        void noMatch() {
            fixtures.customer("Emma Wilson");

            assertThat(items(search(tokens.admin(), "zzzz"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("Search query validation")
    class QueryValidation {

        @Test
        @DisplayName("Missing query")
        void missingQuery() {
            problem(http.get().uri("/api/v1/customers").headers(headers -> headers.setBearerAuth(tokens.admin()))
                    .exchange(), 400, "VALIDATION_FAILED")
                    .jsonPath("$.errors[*].field").value((List<String> fields) -> assertThat(fields).containsExactly("q"));
        }

        @Test
        @DisplayName("Query too short")
        void queryTooShort() {
            problem(search(tokens.admin(), "d"), 400, "VALIDATION_FAILED")
                    .jsonPath("$.errors[*].field").value((List<String> fields) -> assertThat(fields).containsExactly("q"));
            problem(search(tokens.admin(), "  d  "), 400, "VALIDATION_FAILED");
        }
    }

    @Nested
    @DisplayName("Customer search is for staff only")
    @ExtendWith(OutputCaptureExtension.class)
    class StaffOnly {

        @Test
        @DisplayName("Advisor and admin may search")
        void advisorAndAdminMaySearch() {
            fixtures.customer("Lê Minh Đức");

            assertThat(names(search(tokens.advisor(fixtures.dealership("Saigon")), "duc"))).containsExactly("Lê Minh Đức");
            assertThat(names(search(tokens.admin(), "duc"))).containsExactly("Lê Minh Đức");
        }

        @Test
        @DisplayName("Customer may not search")
        void customerMayNotSearch() {
            long duc = fixtures.customer("Lê Minh Đức");

            problem(search(tokens.customer(duc), "duc"), 403, "FORBIDDEN");
        }

        @Test
        @DisplayName("Advisor without a dealership")
        void advisorWithoutADealership() {
            problem(search(tokens.advisorWithoutDealershipId(), "duc"), 403, "FORBIDDEN");
        }

        @Test
        @DisplayName("Search without a token")
        void searchWithoutAToken() {
            problem(http.get().uri("/api/v1/customers?q=duc").exchange(), 401, "UNAUTHENTICATED");
        }

        @Test
        @DisplayName("Search text is not logged")
        void searchTextIsNotLogged(CapturedOutput output) {
            fixtures.customer("Lê Minh Đức");

            http.get().uri("/api/v1/customers?q={q}", "Lê Minh Đức")
                    .header("X-Request-Id", "search-log-1")
                    .headers(headers -> headers.setBearerAuth(tokens.admin()))
                    .exchange()
                    .expectStatus().isOk();

            await().atMost(Duration.ofSeconds(5)).until(() -> output.getOut().contains("GET /api/v1/customers -> 200"));
            assertThat(output.getOut().lines().filter(line -> line.contains("[search-log-1")))
                    .isNotEmpty()
                    .noneMatch(line -> line.contains("Minh") || line.contains("%C3") || line.contains("q="));
        }
    }

    private ResponseSpec search(String token, String query) {
        return http.get().uri("/api/v1/customers?q={q}", query).headers(headers -> headers.setBearerAuth(token)).exchange();
    }

    private static List<String> names(ResponseSpec response) {
        return items(response).stream().map(item -> (String) item.get("name")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(ResponseSpec response) {
        return (List<Map<String, Object>>) response.expectStatus().isOk().expectBody(JSON_OBJECT).returnResult()
                .getResponseBody().get("items");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> vehicles(Map<String, Object> customer) {
        return (List<Map<String, Object>>) customer.get("vehicles");
    }
}
