package com.jaframart.storefront.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  SecurityContextRepository securityContextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
    return configuration.getAuthenticationManager();
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http,
                                          SecurityContextRepository securityContextRepository) throws Exception {
    http
        .csrf(AbstractHttpConfigurer::disable)
        .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(
                "/login.html",
                "/css/**",
                "/js/**",
                "/api/auth/login",
                "/actuator/health/**",
                "/api/info")
            .permitAll()
            .requestMatchers("/admin.html", "/api/admin/**").hasRole("ADMIN")
            .anyRequest().hasAnyRole("USER", "ADMIN"))
        .exceptionHandling(ex -> ex
            .authenticationEntryPoint(entryPoint())
            .accessDeniedHandler(accessDeniedHandler()))
        .logout(AbstractHttpConfigurer::disable);
    return http.build();
  }

  private static AuthenticationEntryPoint entryPoint() {
    return (request, response, authException) -> {
      if (isApi(request)) {
        writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "{\"error\":\"UNAUTHORIZED\"}");
      } else {
        response.sendRedirect("/login.html");
      }
    };
  }

  private static AccessDeniedHandler accessDeniedHandler() {
    return (request, response, accessDeniedException) -> {
      if (isApi(request)) {
        writeJson(response, HttpServletResponse.SC_FORBIDDEN, "{\"error\":\"FORBIDDEN\"}");
      } else {
        response.sendRedirect("/login.html");
      }
    };
  }

  private static boolean isApi(HttpServletRequest request) {
    String uri = request.getRequestURI();
    return uri != null && uri.startsWith("/api/");
  }

  private static void writeJson(HttpServletResponse response, int status, String body)
      throws java.io.IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
  }
}
