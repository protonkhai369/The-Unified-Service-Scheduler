package com.keyloop.scheduler.security;

import jakarta.servlet.DispatcherType;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import com.keyloop.scheduler.web.error.ProblemAccessDeniedHandler;
import com.keyloop.scheduler.web.error.ProblemAuthenticationEntryPoint;

/**
 * URL rules (access-control spec): a short list of public endpoints, the actuator for admins, and a
 * valid Supabase access token for everything else, including paths that do not exist. Rules that need
 * the request body or the appointment live in {@link AccessPolicy}. Stateless: no session, no cookie,
 * hence no CSRF protection to need. The token decoder is Spring Boot's, configured in
 * {@code application.yml}.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        ProblemAuthenticationEntryPoint unauthenticated = new ProblemAuthenticationEntryPoint();
        ProblemAccessDeniedHandler forbidden = new ProblemAccessDeniedHandler();
        return http
                .authorizeHttpRequests(requests -> requests
                        // The error dispatch renders the 401/403/500 problems of the original request.
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                        // Every method, so an unsupported one still answers 405 rather than 401.
                        .requestMatchers("/api/v1/service-types", "/api/v1/dealerships", "/api/v1/dealerships/{id}")
                        .permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole(Role.ADMIN.name())
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new SupabaseJwtConverter()))
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(forbidden))
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterAfter(new UserIdLogFilter(), BearerTokenAuthenticationFilter.class)
                .build();
    }
}
