package com.keyloop.scheduler.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import com.keyloop.scheduler.appointment.AppointmentApiTestSupport;
import com.keyloop.scheduler.security.TestTokens;

class AccountIntegrationTest extends AppointmentApiTestSupport {

    @Nested
    @DisplayName("Caller profile")
    class CallerProfile {

        @Test
        @DisplayName("Customer profile")
        void customerProfile() {
            long duc = fixtures.customer("Le Minh Duc");

            Map<String, Object> body = profile(me(tokens.customer(duc)));

            assertThat(body).containsEntry("userId", TestTokens.USER_ID.toString())
                    .containsEntry("role", "CUSTOMER")
                    .containsEntry("customer", Map.of("id", (int) duc, "name", "Le Minh Duc"))
                    .containsEntry("dealership", null);
        }

        @Test
        @DisplayName("Advisor profile")
        void advisorProfile() {
            long saigon = fixtures.dealership("Saigon Auto Centre", "Asia/Ho_Chi_Minh", LocalTime.of(7, 30),
                    LocalTime.of(17, 30));

            Map<String, Object> body = profile(me(tokens.advisor(saigon)));

            assertThat(body).containsEntry("role", "ADVISOR")
                    .containsEntry("dealership", Map.of("id", (int) saigon, "name", "Saigon Auto Centre"))
                    .containsEntry("customer", null);
        }

        @Test
        @DisplayName("Admin profile")
        void adminProfile() {
            assertThat(profile(me(tokens.admin()))).containsEntry("role", "ADMIN")
                    .containsEntry("customer", null)
                    .containsEntry("dealership", null);
        }

        @Test
        @DisplayName("Token without a role")
        void tokenWithoutRole() {
            assertThat(profile(me(tokens.withoutRole()))).containsEntry("userId", TestTokens.USER_ID.toString())
                    .containsEntry("role", null)
                    .containsEntry("customer", null)
                    .containsEntry("dealership", null);
        }

        @Test
        @DisplayName("Link to a missing record")
        void linkToAMissingRecord() {
            assertThat(profile(me(tokens.customer(999)))).containsEntry("role", "CUSTOMER")
                    .containsEntry("customer", null);
        }

        @Test
        @DisplayName("Profile without a token")
        void profileWithoutToken() {
            problem(http.get().uri("/api/v1/me").exchange(), 401, "UNAUTHENTICATED");
        }
    }

    @Nested
    @DisplayName("Caller's vehicles")
    class CallersVehicles {

        @Test
        @DisplayName("Customer lists their vehicles")
        void customerListsTheirVehicles() {
            long emma = fixtures.customer("Emma");
            long golf = fixtures.vehicle(emma, "WVWZZZ1KZ8W000001", "Volkswagen", "Golf", 2019);
            long corolla = fixtures.vehicle(emma, "JTDBR32E720000002", "Toyota", "Corolla", 2021);
            fixtures.vehicle(fixtures.customer("Duc"), "WF0AXXGCDA0000003", "Ford", "Focus", 2018);

            List<Map<String, Object>> items = items(myVehicles(tokens.customer(emma)).expectStatus().isOk());

            assertThat(items).extracting(item -> ((Number) item.get("id")).longValue()).containsExactly(golf, corolla);
            assertThat(items.get(1)).containsEntry("vin", "JTDBR32E720000002")
                    .containsEntry("make", "Toyota")
                    .containsEntry("model", "Corolla")
                    .containsEntry("modelYear", 2021);
        }

        @Test
        @DisplayName("Customer without vehicles")
        void customerWithoutVehicles() {
            long emma = fixtures.customer("Emma");

            assertThat(items(myVehicles(tokens.customer(emma)).expectStatus().isOk())).isEmpty();
        }

        @Test
        @DisplayName("Staff have no vehicles of their own")
        void staffHaveNoVehiclesOfTheirOwn() {
            problem(myVehicles(tokens.advisor(fixtures.dealership("Saigon"))), 403, "FORBIDDEN");
            problem(myVehicles(tokens.admin()), 403, "FORBIDDEN");
        }

        @Test
        @DisplayName("Customer token without a customer id")
        void customerTokenWithoutCustomerId() {
            problem(myVehicles(tokens.customerWithoutCustomerId()), 403, "FORBIDDEN");
        }
    }

    private ResponseSpec me(String token) {
        return http.get().uri("/api/v1/me").headers(headers -> headers.setBearerAuth(token)).exchange();
    }

    private ResponseSpec myVehicles(String token) {
        return http.get().uri("/api/v1/me/vehicles").headers(headers -> headers.setBearerAuth(token)).exchange();
    }

    private static Map<String, Object> profile(ResponseSpec response) {
        return response.expectStatus().isOk().expectBody(JSON_OBJECT).returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(ResponseSpec response) {
        return (List<Map<String, Object>>) response.expectBody(JSON_OBJECT).returnResult().getResponseBody().get("items");
    }
}
