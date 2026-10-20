package com.jaframart.storefront.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class DemoUsers {

  @Bean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  InMemoryUserDetailsManager userDetailsService(PasswordEncoder encoder) {
    // DelegatingPasswordEncoder.encode adds the {bcrypt} prefix (§9.2). One encode per distinct password.
    String adminHash = encoder.encode("admin");
    String userHash = encoder.encode("user");
    String customerHash = encoder.encode("customer");

    List<UserDetails> users = new ArrayList<>(202);
    users.add(User.withUsername("admin").password(adminHash).roles("ADMIN").build());
    users.add(User.withUsername("user").password(userHash).roles("USER").build());
    for (int n = 1; n <= 200; n++) {
      String id = String.format("customer%03d", n);
      users.add(User.withUsername(id).password(customerHash).roles("USER").build());
    }
    return new InMemoryUserDetailsManager(users);
  }
}
