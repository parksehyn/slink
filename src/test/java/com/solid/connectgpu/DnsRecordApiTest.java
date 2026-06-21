package com.solid.connectgpu;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class DnsRecordApiTest {

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    String apiKey;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        long ts = System.nanoTime();
        String email = "dns-test-" + ts + "@dankook.ac.kr";
        String studentId = "D" + Math.abs(ts % 10_000_000L);
        String regJson = mvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentId + "\",\"email\":\"" + email + "\"}"))
                .andReturn().getResponse().getContentAsString();
        apiKey = JsonPath.read(regJson, "$.apiKey");
    }

    private String createRecord(String body) throws Exception {
        return mvc.perform(post("/api/dns/records")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", "Bearer " + apiKey))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void createA_returnsRecord() throws Exception {
        mvc.perform(post("/api/dns/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"A\",\"name\":\"app1.solid.internal\",\"value\":\"10.0.10.11\"}")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.type").value("A"))
                .andExpect(jsonPath("$.name").value("app1.solid.internal"))
                .andExpect(jsonPath("$.value").value("10.0.10.11"));
    }

    @Test
    void createCname_returnsRecord() throws Exception {
        mvc.perform(post("/api/dns/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CNAME\",\"name\":\"alias.solid.internal\",\"value\":\"app1.solid.internal\"}")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("CNAME"))
                .andExpect(jsonPath("$.value").value("app1.solid.internal"));
    }

    @Test
    void createA_invalidIp_returns400() throws Exception {
        mvc.perform(post("/api/dns/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"A\",\"name\":\"bad.solid.internal\",\"value\":\"not-an-ip\"}")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("IPv4")));
    }

    @Test
    void create_invalidName_returns400() throws Exception {
        mvc.perform(post("/api/dns/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"A\",\"name\":\"BAD NAME\",\"value\":\"10.0.0.1\"}")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_duplicateName_returns400() throws Exception {
        createRecord("{\"type\":\"A\",\"name\":\"dup.solid.internal\",\"value\":\"10.0.0.5\"}");
        mvc.perform(post("/api/dns/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"A\",\"name\":\"dup.solid.internal\",\"value\":\"10.0.0.6\"}")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("already in use")));
    }

    @Test
    void list_returnsOwnRecords() throws Exception {
        createRecord("{\"type\":\"A\",\"name\":\"list-a.solid.internal\",\"value\":\"10.0.1.1\"}");
        createRecord("{\"type\":\"A\",\"name\":\"list-b.solid.internal\",\"value\":\"10.0.1.2\"}");
        mvc.perform(get("/api/dns/records").header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name",
                        hasItems("list-a.solid.internal", "list-b.solid.internal")));
    }

    @Test
    void update_changesValue() throws Exception {
        String id = JsonPath.read(
                createRecord("{\"type\":\"A\",\"name\":\"upd.solid.internal\",\"value\":\"10.0.2.1\"}"), "$.id");
        mvc.perform(patch("/api/dns/records/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"10.0.2.99\"}")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("10.0.2.99"));
    }

    @Test
    void delete_thenGoneFromList() throws Exception {
        String id = JsonPath.read(
                createRecord("{\"type\":\"A\",\"name\":\"del.solid.internal\",\"value\":\"10.0.3.1\"}"), "$.id");
        mvc.perform(delete("/api/dns/records/" + id).header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/dns/records").header("Authorization", "Bearer " + apiKey))
                .andExpect(jsonPath("$[*].name", not(hasItem("del.solid.internal"))));
    }

    @Test
    void unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/dns/records")).andExpect(status().isUnauthorized());
    }

    @Test
    void otherUsersRecord_notDeletable() throws Exception {
        String id = JsonPath.read(
                createRecord("{\"type\":\"A\",\"name\":\"mine.solid.internal\",\"value\":\"10.0.4.1\"}"), "$.id");

        long ts = System.nanoTime();
        String otherEmail = "dns-other-" + ts + "@dankook.ac.kr";
        String otherReg = mvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"X" + Math.abs(ts % 10_000_000L) + "\",\"email\":\"" + otherEmail + "\"}"))
                .andReturn().getResponse().getContentAsString();
        String otherKey = JsonPath.read(otherReg, "$.apiKey");

        mvc.perform(delete("/api/dns/records/" + id).header("Authorization", "Bearer " + otherKey))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_defaultTtlIs3600() throws Exception {
        mvc.perform(post("/api/dns/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"A\",\"name\":\"ttl-default.solid.internal\",\"value\":\"10.0.5.1\"}")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ttl").value(3600));
    }

    @Test
    void create_customTtl_stored() throws Exception {
        mvc.perform(post("/api/dns/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"A\",\"name\":\"ttl-custom.solid.internal\",\"value\":\"10.0.5.2\",\"ttl\":300}")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ttl").value(300));
    }
}
