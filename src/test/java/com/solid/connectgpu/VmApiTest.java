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
class VmApiTest {

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    String apiKey;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        long ts = System.nanoTime();
        String email = "vm-test-" + ts + "@dankook.ac.kr";
        String studentId = "V" + Math.abs(ts % 10_000_000L);
        String regJson = mvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentId + "\",\"email\":\"" + email + "\"}"))
                .andReturn().getResponse().getContentAsString();
        apiKey = JsonPath.read(regJson, "$.apiKey");
    }

    @Test
    void list_returnsVms() throws Exception {
        mvc.perform(get("/api/vms").header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].instanceId").isNotEmpty())
                .andExpect(jsonPath("$[0].privateIp").isNotEmpty());
    }

    @Test
    void unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/vms")).andExpect(status().isUnauthorized());
    }
}
