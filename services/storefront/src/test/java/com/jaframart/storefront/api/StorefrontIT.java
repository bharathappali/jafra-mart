package com.jaframart.storefront.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class StorefrontIT {
  @Autowired
  MockMvc mockMvc;

  @Test
  void loginLogoutAndStaticPages() throws Exception {
    MvcResult login = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"user\",\"password\":\"user\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("user"))
        .andExpect(jsonPath("$.displayName").value("Demo User"))
        .andExpect(jsonPath("$.roles[0]").value("USER"))
        .andReturn();
    MockHttpSession session = (MockHttpSession) login.getRequest().getSession();

    mockMvc.perform(get("/api/me").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("user"));

    mockMvc.perform(get("/shop.html").session(session))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("JafraMart")));

    mockMvc.perform(get("/login.html"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Demo logins")));

    mockMvc.perform(post("/api/auth/logout").session(session))
        .andExpect(status().isNoContent());

    mockMvc.perform(get("/api/me").session(session))
        .andExpect(status().isUnauthorized());

    MvcResult adminLogin = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin\",\"password\":\"admin\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roles[0]").value("ADMIN"))
        .andReturn();
    MockHttpSession adminSession = (MockHttpSession) adminLogin.getRequest().getSession();
    mockMvc.perform(get("/admin.html").session(adminSession))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Admin")));

    mockMvc.perform(get("/api/info"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.component").value("storefront"))
        .andExpect(jsonPath("$.framework").value("spring-boot 4.1.1"));
  }
}
