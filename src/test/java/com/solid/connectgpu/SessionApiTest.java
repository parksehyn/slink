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

import static org.hamcrest.Matchers.hasLength;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class SessionApiTest {

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    String ownerEmail;
    String apiKey;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        long ts = System.nanoTime();
        ownerEmail = "session-test-" + ts + "@dankook.ac.kr";
        String studentId = "S" + Math.abs(ts % 10_000_000L);
        String regJson = mvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentId + "\",\"email\":\"" + ownerEmail + "\"}"))
                .andReturn().getResponse().getContentAsString();
        apiKey = JsonPath.read(regJson, "$.apiKey");
    }

    private String sessionJson(String owner, String token) {
        return "{\"owner\":\"" + owner + "\","
                + "\"ngrokHost\":\"https://test.trycloudflare.com\","
                + "\"sshPort\":0,\"otp\":\"\","
                + "\"jupyterToken\":\"" + token + "\"}";
    }

    @Test
    void register_and_getByCode() throws Exception {
        String regJson = mvc.perform(post("/api/session/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(ownerEmail, "tok-abc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(hasLength(6)))
                .andReturn().getResponse().getContentAsString();

        String code = JsonPath.read(regJson, "$.code");
        mvc.perform(get("/api/session/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jupyterToken").value("tok-abc"));
    }

    @Test
    void getByCode_notFound_returns404() throws Exception {
        mvc.perform(get("/api/session/000000"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getByOwner_withValidAuth_returnsSession() throws Exception {
        mvc.perform(post("/api/session/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(ownerEmail, "tok-owner")))
                .andExpect(status().isOk());

        mvc.perform(get("/api/session/by-owner/" + ownerEmail)
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jupyterToken").value("tok-owner"));
    }

    @Test
    void getByOwner_withWrongKey_returns401() throws Exception {
        mvc.perform(get("/api/session/by-owner/" + ownerEmail)
                        .header("Authorization", "Bearer sk-dku-wrongkey000000000000000000"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteByCode_thenNotFound() throws Exception {
        String email2 = ownerEmail + "del";
        String regJson = mvc.perform(post("/api/session/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(email2, "")))
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(regJson, "$.code");

        mvc.perform(delete("/api/session/" + code)).andExpect(status().isNoContent());
        mvc.perform(get("/api/session/" + code)).andExpect(status().isNotFound());
    }

    @Test
    void reregister_sameOwner_replacesSession() throws Exception {
        mvc.perform(post("/api/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(sessionJson(ownerEmail, "tok-v1")));

        mvc.perform(post("/api/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(sessionJson(ownerEmail, "tok-v2")));

        mvc.perform(get("/api/session/by-owner/" + ownerEmail)
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jupyterToken").value("tok-v2"));
    }
}
