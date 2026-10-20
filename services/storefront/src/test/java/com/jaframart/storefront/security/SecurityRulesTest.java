package com.jaframart.storefront.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityRulesTest {
  @Autowired
  MockMvc mockMvc;

  @Test
  void anonymousAdminSummaryIs401() throws Exception {
    mockMvc.perform(get("/api/admin/summary"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
  }

  @Test
  void userCannotGetAdminSummary() throws Exception {
    MvcResult login = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"user\",\"password\":\"user\"}"))
        .andExpect(status().isOk())
        .andReturn();
    MockHttpSession session = (MockHttpSession) login.getRequest().getSession();

    mockMvc.perform(get("/api/admin/summary").session(session))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("FORBIDDEN"));
  }
}
