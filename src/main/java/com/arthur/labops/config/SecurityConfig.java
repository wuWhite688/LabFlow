package com.arthur.labops.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.MediaType;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.arthur.labops.auth.JwtAuthenticationFilter;
import com.arthur.labops.auth.JwtProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            JwtAuthenticationFilter jwtAuthenticationFilter,
                                            ObjectMapper objectMapper)
            throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh", "/api/auth/logout")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/equipment").hasAnyRole("TEACHER", "ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/equipment/**").hasAnyRole("TEACHER", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/equipment/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/reservations").hasAnyRole("STUDENT", "TEACHER", "ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/reservations/*/decision").hasAnyRole("TEACHER", "ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/reservations/*/complete").hasAnyRole("TEACHER", "ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/reservations/*/cancel").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/reservations/**").authenticated()
                        // The channel has no platform account; the endpoint verifies a
                        // shared token itself, standing in for signature verification.
                        .requestMatchers(HttpMethod.POST, "/api/payments/callback").permitAll()
                        .requestMatchers("/api/payments/**").authenticated()
                        .requestMatchers("/api/reconciliation/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/work-orders").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/work-orders/**").hasAnyRole("TECHNICIAN", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/work-orders/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/users/technicians").hasAnyRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/audit-logs/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/dashboard/**").authenticated()
                        .anyRequest().authenticated())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(response.getOutputStream(), Map.of(
                                    "timestamp", Instant.now().toString(),
                                    "status", 401,
                                    "code", "UNAUTHORIZED",
                                    "message", "请先登录或令牌已失效",
                                    "fields", Map.of()));
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(response.getOutputStream(), Map.of(
                                    "timestamp", Instant.now().toString(),
                                    "status", 403,
                                    "code", "ACCESS_DENIED",
                                    "message", "当前角色无权执行该操作",
                                    "fields", Map.of()));
                        }))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
