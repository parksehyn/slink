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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 학생당 서비스 제한 검증: service.max-per-owner / service.max-public-per-owner 초과 시 400.
 */
@SpringBootTest(properties = {
        "service.max-per-owner=2",
        "service.max-public-per-owner=1"
})
class ServiceLimitTest {

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    String token;
    String vmId;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        long ts = System.nanoTime();
        String username = String.valueOf(10_000_000L + Math.abs(ts % 80_000_000L));
        token = JsonPath.read(mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"pw\"}"))
                .andReturn().getResponse().getContentAsString(), "$.token");
        vmId = JsonPath.read(mvc.perform(get("/api/vms").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString(), "$[0].instanceId");
    }

    private String create(String name) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"instanceId\":\"" + vmId + "\","
                + "\"localPort\":3000,\"protocol\":\"HTTP\",\"scope\":\"INTERNAL\"}";
        return mvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void create_overMaxPerOwner_returns400() throws Exception {
        create("limit-a");
        create("limit-b");   // 2개까지 OK (상한 2)
        mvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"limit-c\",\"instanceId\":\"" + vmId + "\","
                                + "\"localPort\":3000,\"protocol\":\"HTTP\",\"scope\":\"INTERNAL\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("상한")));
    }

    @Test
    void publish_overMaxPublicPerOwner_returns400() throws Exception {
        String idA = JsonPath.read(create("pub-a"), "$.id");
        String idB = JsonPath.read(create("pub-b"), "$.id");

        mvc.perform(post("/api/services/" + idA + "/publish")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ttlHours\":2}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());   // 첫 공개 OK (상한 1)

        mvc.perform(post("/api/services/" + idB + "/publish")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ttlHours\":2}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("상한")));
    }
}
