package com.jaframart.catalog.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class CatalogSearchIT {
  @Test
  void endpointsAndScenarioContract() {
    given().queryParam("page", 0).queryParam("size", 24).queryParam("category", "Home")
        .when().get("/api/products")
        .then().statusCode(200).body("page", equalTo(0)).body("size", equalTo(24)).body("total", greaterThan(0));

    given().when().get("/api/products/SKU-00042")
        .then().statusCode(200)
        .body("sku", equalTo("SKU-00042"))
        .body("name", equalTo("Frosted Glass Smart Lamp"))
        .body("priceCents", equalTo(12999));

    given().when().get("/api/products/SKU-99999").then().statusCode(404).body("error", equalTo("NOT_FOUND"));

    given().queryParam("q", "lamp").queryParam("limit", 20).queryParam("fuzzy", false)
        .when().get("/api/search")
        .then().statusCode(200).body("fuzzy", equalTo(false)).body("count", equalTo(20));

    given().queryParam("q", "frostd lmp").queryParam("limit", 20).queryParam("fuzzy", true)
        .when().get("/api/search")
        .then().statusCode(200).body("fuzzy", equalTo(true)).body("items.sku", hasItem("SKU-00042"));

    given().when().get("/api/flash").then().statusCode(200).body("active", equalTo(false)).body("sku", equalTo("SKU-00042"));
    given().contentType("application/json").body("{\"active\":true}")
        .when().put("/api/flash")
        .then().statusCode(200).body("active", equalTo(true)).body("discountPercent", equalTo(20));

    given().queryParam("size", 128).when().get("/api/products/SKU-00042/thumbnail")
        .then().statusCode(200).header("Content-Type", startsWith("image/png"));
    given().queryParam("size", 32).when().get("/api/products/SKU-00042/thumbnail").then().statusCode(400);

    given().when().get("/api/info").then().statusCode(200)
        .body("component", equalTo("catalog-search"))
        .body("framework", equalTo("quarkus 3.40.1"))
        .body("gc", equalTo("G1"));

    given().when().get("/internal/scenarios").then().statusCode(200).body("id", hasItem("S01")).body("id", hasItem("S10"));
    given().contentType("application/json").body("{}")
        .when().post("/internal/scenarios/S01/start")
        .then().statusCode(202).body("state", equalTo("RAMPING"));
    given().contentType("application/json").body("{}")
        .when().post("/internal/scenarios/S01/start")
        .then().statusCode(409);
    given().when().post("/internal/scenarios/S01/reset").then().statusCode(200).body("state", equalTo("IDLE"));
    given().when().post("/internal/scenarios/reset-all").then().statusCode(200);
  }
}
