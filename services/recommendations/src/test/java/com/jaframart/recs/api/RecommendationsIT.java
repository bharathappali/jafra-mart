package com.jaframart.recs.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RecommendationsIT {
  @Autowired
  MockMvc mockMvc;

  @Test
  void endpointsAndScenarioContract() throws Exception {
    mockMvc.perform(get("/api/recommendations")
            .param("customerId", "customer007")
            .param("productId", "SKU-00042")
            .param("sessionId", "abc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.productId").value("SKU-00042"))
        .andExpect(jsonPath("$.items", hasSize(8)))
        .andExpect(jsonPath("$.cache").value("MISS"));

    mockMvc.perform(get("/api/recommendations")
            .param("customerId", "customer007")
            .param("productId", "SKU-00042")
            .param("sessionId", "abc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cache").value("HIT"));

    mockMvc.perform(get("/api/recommendations/cache"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.evictionEnabled").value(true))
        .andExpect(jsonPath("$.maxEntries").value(1000))
        .andExpect(jsonPath("$.entries").value(1));

    mockMvc.perform(get("/api/info")).andExpect(status().isOk())
        .andExpect(jsonPath("$.component").value("recommendations"))
        .andExpect(jsonPath("$.framework").value("spring-boot 4.1.1"));

    mockMvc.perform(get("/internal/scenarios")).andExpect(status().isOk())
        .andExpect(jsonPath("$[*].id", hasItem("S05")));
    mockMvc.perform(post("/internal/scenarios/S05/start").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.state", equalTo("RAMPING")));
    mockMvc.perform(post("/internal/scenarios/S05/start").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isConflict());
    mockMvc.perform(post("/internal/scenarios/S05/reset")).andExpect(status().isOk())
        .andExpect(jsonPath("$.state", equalTo("IDLE")));
  }
}
