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

/**
 * 외부 자원 연결 API 테스트. 포털(slk-)이 단기 등록 토큰(rt-)을 발급하고, 외부 에이전트가
 * rt-로 등록해 rat- 토큰을 받은 뒤 heartbeat/report 한다.
 */
@SpringBootTest
class ResourceApiTest {

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    String token;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        token = login(randomUser());
    }

    private static String randomUser() {
        long ts = System.nanoTime();
        return String.valueOf(10_000_000L + Math.abs(ts % 80_000_000L));
    }

    private String login(String username) throws Exception {
        String json = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.token");
    }

    /** rt- 등록 토큰 발급(slk-). */
    private String issueToken(String tok, String resourceType, String name) throws Exception {
        String json = mvc.perform(post("/api/resources/registration-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceType\":\"" + resourceType + "\",\"name\":\"" + name + "\"}")
                        .header("Authorization", "Bearer " + tok))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.token");
    }

    /** rt-로 자원 등록 → 응답 JSON 문자열. */
    private String register(String rt, String body) throws Exception {
        return mvc.perform(post("/api/resources/agents/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Registration-Token", rt))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void issueToken_requiresAuth() throws Exception {
        mvc.perform(post("/api/resources/registration-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceType\":\"COLAB_GPU\",\"name\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/resources")).andExpect(status().isUnauthorized());
    }

    @Test
    void register_withToken_returnsAgentToken() throws Exception {
        String rt = issueToken(token, "COLAB_GPU", "colab-a");
        String res = register(rt, "{}");
        org.junit.jupiter.api.Assertions.assertEquals("COLAB_GPU", JsonPath.read(res, "$.resourceType"));
        org.junit.jupiter.api.Assertions.assertEquals("colab-a", JsonPath.read(res, "$.name"));
        String agentToken = JsonPath.read(res, "$.agentToken");
        org.junit.jupiter.api.Assertions.assertTrue(agentToken.startsWith("rat-"));
    }

    @Test
    void register_badToken_returns401() throws Exception {
        mvc.perform(post("/api/resources/agents/register")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("X-Registration-Token", "rt-doesnotexist"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registrationToken_isSingleUse() throws Exception {
        String rt = issueToken(token, "JUPYTER", "jup-a");
        // 1회차 성공
        mvc.perform(post("/api/resources/agents/register")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("X-Registration-Token", rt))
                .andExpect(status().isCreated());
        // 2회차 동일 토큰 → 401 (단일 사용)
        mvc.perform(post("/api/resources/agents/register")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("X-Registration-Token", rt))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void report_ready_makesResourceActive() throws Exception {
        String rt = issueToken(token, "HTTP_API", "http-a");
        String res = register(rt, "{}");
        String id = JsonPath.read(res, "$.resourceId");
        String rat = JsonPath.read(res, "$.agentToken");

        mvc.perform(post("/api/resources/agents/" + id + "/report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"RESOURCE_READY\",\"publicUrl\":\"https://x.trycloudflare.com\","
                                + "\"serviceToken\":\"jt-123\"}")
                        .header("X-Agent-Token", rat))
                .andExpect(status().isOk());

        // 상세: ACTIVE + publicUrl + serviceToken(상세에서만 노출)
        mvc.perform(get("/api/resources/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.publicUrl").value("https://x.trycloudflare.com"))
                .andExpect(jsonPath("$.serviceToken").value("jt-123"));
    }

    @Test
    void list_masksServiceToken() throws Exception {
        String rt = issueToken(token, "HTTP_API", "http-mask");
        String res = register(rt, "{\"publicUrl\":\"https://m.trycloudflare.com\",\"serviceToken\":\"secret\"}");
        JsonPath.read(res, "$.resourceId");
        mvc.perform(get("/api/resources").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='http-mask')].serviceToken", everyItem(nullValue())));
    }

    @Test
    void heartbeat_wrongToken_returns401() throws Exception {
        String rt = issueToken(token, "JUPYTER", "hb-a");
        String id = JsonPath.read(register(rt, "{}"), "$.resourceId");
        mvc.perform(post("/api/resources/agents/" + id + "/heartbeat")
                        .header("X-Agent-Token", "rat-wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void heartbeat_validToken_returnsNotRevoked() throws Exception {
        String rt = issueToken(token, "JUPYTER", "hb-ok");
        String res = register(rt, "{}");
        String id = JsonPath.read(res, "$.resourceId");
        String rat = JsonPath.read(res, "$.agentToken");
        mvc.perform(post("/api/resources/agents/" + id + "/heartbeat")
                        .header("X-Agent-Token", rat))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(false));
    }

    @Test
    void ownership_otherUserCannotSeeOrDelete() throws Exception {
        String rt = issueToken(token, "COLAB_GPU", "owned");
        String id = JsonPath.read(register(rt, "{}"), "$.resourceId");

        String other = login(randomUser());
        mvc.perform(get("/api/resources/" + id).header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/resources/" + id).header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound());
        // 소유자는 여전히 접근 가능
        mvc.perform(get("/api/resources/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void delete_thenGone() throws Exception {
        String rt = issueToken(token, "HTTP_API", "to-del");
        String id = JsonPath.read(register(rt, "{}"), "$.resourceId");
        mvc.perform(delete("/api/resources/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/resources/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
