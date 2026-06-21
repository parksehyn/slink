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
class ConnectionApiTest {

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    String apiKey;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        long ts = System.nanoTime();
        String email = "conn-test-" + ts + "@dankook.ac.kr";
        String studentId = "C" + Math.abs(ts % 10_000_000L);
        String regJson = mvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentId + "\",\"email\":\"" + email + "\"}"))
                .andReturn().getResponse().getContentAsString();
        apiKey = JsonPath.read(regJson, "$.apiKey");
    }

    private String create(String body) throws Exception {
        return mvc.perform(post("/api/connections")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", "Bearer " + apiKey))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void create_returnsConnection() throws Exception {
        mvc.perform(post("/api/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"colab-gpu\",\"type\":\"JUPYTER\","
                                + "\"url\":\"https://xxxx.trycloudflare.com\",\"token\":\"abc\"}")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("colab-gpu"))
                .andExpect(jsonPath("$.type").value("JUPYTER"))
                .andExpect(jsonPath("$.url").value("https://xxxx.trycloudflare.com"))
                .andExpect(jsonPath("$.token").value("abc"));
    }

    @Test
    void create_missingUrl_returns400() throws Exception {
        mvc.perform(post("/api/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"no-url\",\"type\":\"HTTP\"}")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("URL")));
    }

    @Test
    void create_jupyterNonHttpUrl_returns400() throws Exception {
        mvc.perform(post("/api/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad-url\",\"type\":\"JUPYTER\",\"url\":\"ftp://nope\"}")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list_returnsOwn() throws Exception {
        create("{\"name\":\"c-list-a\",\"type\":\"HTTP\",\"url\":\"https://a.example.com\"}");
        create("{\"name\":\"c-list-b\",\"type\":\"OTHER\",\"url\":\"tcp://b:22\"}");
        mvc.perform(get("/api/connections").header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItems("c-list-a", "c-list-b")));
    }

    @Test
    void delete_thenGone() throws Exception {
        String id = JsonPath.read(
                create("{\"name\":\"c-del\",\"type\":\"HTTP\",\"url\":\"https://del.example.com\"}"), "$.id");
        mvc.perform(delete("/api/connections/" + id).header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/connections").header("Authorization", "Bearer " + apiKey))
                .andExpect(jsonPath("$[*].name", not(hasItem("c-del"))));
    }

    @Test
    void unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/connections")).andExpect(status().isUnauthorized());
    }
}
