package com.keyloop.scheduler.customer.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.keyloop.scheduler.customer.entity.Customer;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    @Query(value = """
            WITH input AS (SELECT f_unaccent(lower(:term)) AS q, f_unaccent(lower(:pattern)) AS p)
            SELECT c.* FROM customer c, input
            WHERE f_unaccent(lower(c.name)) LIKE '%' || input.p || '%' ESCAPE '\\'
               OR f_unaccent(lower(c.email)) LIKE '%' || input.p || '%' ESCAPE '\\'
               OR f_unaccent(lower(c.name)) OPERATOR(extensions.%) input.q
            ORDER BY (f_unaccent(lower(c.name)) LIKE '%' || input.p || '%' ESCAPE '\\'
                      OR f_unaccent(lower(c.email)) LIKE '%' || input.p || '%' ESCAPE '\\') DESC,
                     extensions.similarity(f_unaccent(lower(c.name)), input.q) DESC,
                     c.id
            LIMIT 20""", nativeQuery = true)
    List<Customer> searchMatching(String term, String pattern);

    default List<Customer> search(String term) {
        return searchMatching(term, term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"));
    }
}
