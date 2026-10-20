package com.jaframart.inventory.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class InventoryIT {
  @Autowired
  MockMvc mockMvc;

  @Test
  void endpointsAndScenarioContract() throws Exception {
    mockMvc.perform(get("/api/inventory/SKU-00042"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(1_000_000));

    mockMvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON)
            .content("{\"orderNumber\":\"JM-20261019-000123\",\"items\":[{\"sku\":\"SKU-00042\",\"quantity\":2}]}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("HELD"));

    String body = mockMvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON)
            .content("{\"orderNumber\":\"JM-20261019-000124\",\"items\":[{\"sku\":\"SKU-00001\",\"quantity\":1}]}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    String id = body.replaceAll("(?s).*\"reservationId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    mockMvc.perform(post("/api/reservations/" + id + "/commit")).andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMMITTED"));

    mockMvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON)
            .content("{\"orderNumber\":\"JM-OVER\",\"items\":[{\"sku\":\"SKU-00005\",\"quantity\":100000}]}"))
        .andExpect(status().isConflict());

    mockMvc.perform(get("/api/info")).andExpect(status().isOk())
        .andExpect(jsonPath("$.component").value("inventory"))
        .andExpect(jsonPath("$.framework").value("spring-boot 4.1.1"));

    mockMvc.perform(get("/internal/scenarios")).andExpect(status().isOk())
        .andExpect(jsonPath("$[*].id", hasItem("S02")));
    mockMvc.perform(post("/internal/scenarios/S02/start").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.state", equalTo("RAMPING")));
    mockMvc.perform(post("/internal/scenarios/S02/start").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isConflict());
    mockMvc.perform(post("/internal/scenarios/S02/reset")).andExpect(status().isOk())
        .andExpect(jsonPath("$.state", equalTo("IDLE")));
  }
}
