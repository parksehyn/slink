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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 지표 API 테스트 (unified-agent-design.md §8.1 1단계). SOLID 인증 필수,
 * 위치별 Agent 집계가 등록에 반응하는지 확인한다.
 */
@SpringBootTest
class MetricsApiTest {

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    String token;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        long ts = System.nanoTime();
        String username = String.valueOf(10_000_000L + Math.abs(ts % 80_000_000L));
        String json = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        token = JsonPath.read(json, "$.token");
    }

    @Test
    void metrics_requiresAuth() throws Exception {
        mvc.perform(get("/api/metrics")).andExpect(status().isUnauthorized());
    }

    @Test
    void metrics_returnsAggregates() throws Exception {
        mvc.perform(get("/api/metrics").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uptimeSeconds").isNumber())
                .andExpect(jsonPath("$.agents.total").isNumber())
                .andExpect(jsonPath("$.agents.byLocation.SOLID_VM").isNumber())
                .andExpect(jsonPath("$.agents.byLocation.COLAB").isNumber())
                .andExpect(jsonPath("$.agents.byLocation.EXTERNAL").isNumber())
                .andExpect(jsonPath("$.services.total").isNumber())
                .andExpect(jsonPath("$.jvm.maxMemoryMb").isNumber());
    }

    @Test
    void metrics_reflectsExternalAgentRegistration() throws Exception {
        // 등록 전 카운트
        String before = mvc.perform(get("/api/metrics").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        int colabBefore = ((Number) JsonPath.read(before, "$.agents.byLocation.COLAB")).intValue();

        // 외부 Agent(COLAB) 등록: rt- 발급 → register
        String rt = JsonPath.read(mvc.perform(post("/api/resources/registration-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceType\":\"COLAB_GPU\",\"name\":\"metrics-colab-" + System.nanoTime() + "\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.token");
        mvc.perform(post("/api/resources/agents/register")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("X-Registration-Token", rt))
                .andExpect(status().isCreated());

        // COLAB 위치 카운트 +1
        mvc.perform(get("/api/metrics").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents.byLocation.COLAB").value(colabBefore + 1));
    }
}
