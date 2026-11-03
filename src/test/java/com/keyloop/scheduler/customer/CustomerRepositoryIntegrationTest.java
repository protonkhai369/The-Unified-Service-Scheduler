package com.keyloop.scheduler.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.keyloop.scheduler.DatabaseIntegrationTest;
import com.keyloop.scheduler.customer.entity.Customer;
import com.keyloop.scheduler.customer.repository.CustomerRepository;

class CustomerRepositoryIntegrationTest extends DatabaseIntegrationTest {

    @Autowired
    private CustomerRepository customers;

    @Test
    void ignoresCaseAccentsAndNormalisationForm() {
        fixtures.customer("Lê Minh Đức");
        fixtures.customer("Emma Wilson");

        assertThat(names("le minh duc")).containsExactly("Lê Minh Đức");
        assertThat(names("LÊ MINH ĐỨC")).containsExactly("Lê Minh Đức");
        assertThat(names(Normalizer.normalize("Đức", Normalizer.Form.NFD))).containsExactly("Lê Minh Đức");
    }

    @Test
    void findsPartOfANameOrAnEmail() {
        fixtures.customer("Lê Minh Đức", "minh.duc@example.com");
        fixtures.customer("Emma Wilson", "emma.wilson@example.com");

        assertThat(names("duc")).containsExactly("Lê Minh Đức");
        assertThat(names("wil")).containsExactly("Emma Wilson");
        assertThat(names("minh.duc@")).containsExactly("Lê Minh Đức");
    }

    @Test
    void toleratesTypingMistakesAndWordOrder() {
        fixtures.customer("Lê Minh Đức");

        assertThat(names("lee minh duk")).containsExactly("Lê Minh Đức");
        assertThat(names("duc le")).containsExactly("Lê Minh Đức");
    }

    @Test
    void ranksContainmentBeforeSimilarityThenById() {
        long firstTwin = fixtures.customer("Nguyen Van An");
        fixtures.customer("Lee Minh Duk");
        fixtures.customer("Lê Minh Đức");
        long secondTwin = fixtures.customer("Nguyen Van An");

        assertThat(names("le minh duc")).containsExactly("Lê Minh Đức", "Lee Minh Duk");
        assertThat(customers.search("nguyen van an")).extracting(Customer::getId).containsExactly(firstTwin, secondTwin);
    }

    @Test
    void returnsAtMostTwentyCustomers() {
        IntStream.rangeClosed(1, 25).forEach(i -> fixtures.customer("Nguyen " + i));

        assertThat(customers.search("nguyen")).hasSize(20);
    }

    @Test
    void treatsLikeWildcardsLiterally() {
        fixtures.customer("Percent 50% Club");
        fixtures.customer("500 Motors");
        fixtures.customer("A_B Garage");
        fixtures.customer("AXB Garage");

        assertThat(names("50%")).containsExactly("Percent 50% Club");
        assertThat(names("a_b")).containsExactly("A_B Garage");
    }

    @Test
    void findsNothingForAnUnrelatedQuery() {
        fixtures.customer("Emma Wilson");

        assertThat(customers.search("zzzz")).isEmpty();
    }

    private List<String> names(String query) {
        return customers.search(query).stream().map(Customer::getName).toList();
    }
}
