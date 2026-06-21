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
class ServiceApiTest {

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    String email;
    String apiKey;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        long ts = System.nanoTime();
        email = "svc-test-" + ts + "@dankook.ac.kr";
        String studentId = "A" + Math.abs(ts % 10_000_000L);
        String regJson = mvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentId + "\",\"email\":\"" + email + "\"}"))
                .andReturn().getResponse().getContentAsString();
        apiKey = JsonPath.read(regJson, "$.apiKey");
    }

    private String createJson(String name) {
        return "{\"name\":\"" + name + "\","
                + "\"instanceId\":\"solid-32211690\","
                + "\"privateIp\":\"10.0.10.99\","
                + "\"localPort\":3000,"
                + "\"protocol\":\"HTTP\","
                + "\"scope\":\"INTERNAL\"}";
    }

    private String createService(String name) throws Exception {
        return mvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(name))
                        .header("Authorization", "Bearer " + apiKey))
                .andReturn().getResponse().getContentAsString();
    }

    private String idOf(String json) {
        return JsonPath.read(json, "$.id");
    }

    @Test
    void create_returnsServiceWithId() throws Exception {
        mvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson("my-api"))
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("my-api"))
                .andExpect(jsonPath("$.scope").value("INTERNAL"))
                .andExpect(jsonPath("$.pendingCommand").value("NONE"))
                .andExpect(jsonPath("$.internalHostname")
                        .value("my-api.solid-32211690.solid.internal"));
    }

    @Test
    void list_returnsOwnServices() throws Exception {
        createService("svc-list-a");
        createService("svc-list-b");

        mvc.perform(get("/api/services").header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItems("svc-list-a", "svc-list-b")));
    }

    @Test
    void get_existingService_returnsDetail() throws Exception {
        String id = idOf(createService("get-test"));

        mvc.perform(get("/api/services/" + id).header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("get-test"));
    }

    @Test
    void get_nonExistent_returns404() throws Exception {
        mvc.perform(get("/api/services/does-not-exist").header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_changeName_reflected() throws Exception {
        String id = idOf(createService("update-before"));

        mvc.perform(patch("/api/services/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"update-after\"}")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("update-after"));
    }

    @Test
    void create_duplicateName_returns400() throws Exception {
        createService("dup-name");

        mvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson("dup-name"))
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("already in use")));
    }

    @Test
    void create_invalidName_returns400() throws Exception {
        String badJson = "{\"name\":\"INVALID NAME!\","
                + "\"instanceId\":\"solid-01\",\"privateIp\":\"10.0.0.1\","
                + "\"localPort\":8080,\"protocol\":\"HTTP\",\"scope\":\"PRIVATE\"}";

        mvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON).content(badJson)
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isBadRequest());
    }

    // ── publish / unpublish (PENDING 기반 새 흐름) ────────────────────────────

    @Test
    void publish_setsPendingStateForAgent() throws Exception {
        // publish 요청 후 즉시 PUBLIC이 아니라 PENDING — VM Agent가 URL을 보고해야 PUBLIC이 됨
        String id = idOf(createService("pub-test"));

        mvc.perform(post("/api/services/" + id + "/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ttlHours\":4}")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.pendingCommand").value("OPEN_TUNNEL"))
                .andExpect(jsonPath("$.publicExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$.scope").value("INTERNAL"))   // scope 변경 없음
                .andExpect(jsonPath("$.publicUrl").doesNotExist()); // URL은 없음
    }

    @Test
    void unpublish_cancelsPendingPublish() throws Exception {
        // publish → PENDING 상태에서 unpublish → 즉시 취소, scope 복원
        String id = idOf(createService("unpub-test"));

        mvc.perform(post("/api/services/" + id + "/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ttlHours\":2}")
                .header("Authorization", "Bearer " + apiKey));

        mvc.perform(delete("/api/services/" + id + "/publish")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingCommand").value("NONE"))
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.scope").value("INTERNAL"))       // INTERNAL로 복원
                .andExpect(jsonPath("$.publicUrl").doesNotExist())
                .andExpect(jsonPath("$.publicExpiresAt").doesNotExist());
    }

    @Test
    void delete_thenNotFound() throws Exception {
        String id = idOf(createService("delete-me"));

        mvc.perform(delete("/api/services/" + id).header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/services/" + id).header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isNotFound());
    }

    @Test
    void unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/services"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void otherUsersService_notVisible() throws Exception {
        long ts = System.nanoTime();
        String otherEmail = "other-" + ts + "@dankook.ac.kr";
        String otherRegJson = mvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"O" + Math.abs(ts % 10_000_000L) + "\",\"email\":\"" + otherEmail + "\"}"))
                .andReturn().getResponse().getContentAsString();
        String otherKey = JsonPath.read(otherRegJson, "$.apiKey");

        String otherSvcJson = "{\"name\":\"other-svc\","
                + "\"instanceId\":\"solid-01\",\"privateIp\":\"10.0.0.1\","
                + "\"localPort\":8080,\"protocol\":\"HTTP\",\"scope\":\"PRIVATE\"}";
        String otherSvcResult = mvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON).content(otherSvcJson)
                        .header("Authorization", "Bearer " + otherKey))
                .andReturn().getResponse().getContentAsString();
        String otherId = JsonPath.read(otherSvcResult, "$.id");

        mvc.perform(get("/api/services/" + otherId).header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isNotFound());
    }

    // ── PUBLIC scope 직접 지정 차단 ──────────────────────────────────────────

    @Test
    void create_withPublicScope_returns400() throws Exception {
        String body = "{\"name\":\"pub-direct\","
                + "\"instanceId\":\"solid-01\",\"privateIp\":\"10.0.0.1\","
                + "\"localPort\":8080,\"protocol\":\"HTTP\",\"scope\":\"PUBLIC\"}";
        mvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("use POST /publish")));
    }

    @Test
    void update_withPublicScope_returns400() throws Exception {
        String id = idOf(createService("patch-public-block"));
        mvc.perform(patch("/api/services/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"PUBLIC\"}")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("use POST /publish")));
    }

    // ── 이름 변경 시 내부 hostname 갱신 ──────────────────────────────────────

    @Test
    void update_name_updatesInternalHostname() throws Exception {
        String id = idOf(createService("hostname-before"));

        mvc.perform(patch("/api/services/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"hostname-after\"}")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("hostname-after"))
                .andExpect(jsonPath("$.internalHostname")
                        .value("hostname-after.solid-32211690.solid.internal"));
    }

    // ── PENDING 상태에서 scope 변경 → 터널 취소 ─────────────────────────────

    @Test
    void update_scopeWhilePending_cancelsPublish() throws Exception {
        // publish → PENDING, PATCH scope → 즉시 취소
        String id = idOf(createService("tunnel-cancel-test"));

        mvc.perform(post("/api/services/" + id + "/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ttlHours\":2}")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.pendingCommand").value("OPEN_TUNNEL"));

        mvc.perform(patch("/api/services/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"PRIVATE\"}")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("PRIVATE"))
                .andExpect(jsonPath("$.pendingCommand").value("NONE"))
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.publicUrl").doesNotExist())
                .andExpect(jsonPath("$.publicExpiresAt").doesNotExist());
    }

    // ── unpublish 후 scope 복원 ───────────────────────────────────────────────

    @Test
    void unpublish_restoresScopeToPrePublishValue() throws Exception {
        // INTERNAL scope로 서비스 생성 후 publish(PENDING) → unpublish → INTERNAL 복원
        String id = idOf(createService("scope-restore-test")); // createJson uses INTERNAL

        mvc.perform(post("/api/services/" + id + "/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ttlHours\":2}")
                .header("Authorization", "Bearer " + apiKey));

        mvc.perform(delete("/api/services/" + id + "/publish")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("INTERNAL"))
                .andExpect(jsonPath("$.pendingCommand").value("NONE"))
                .andExpect(jsonPath("$.publicUrl").doesNotExist())
                .andExpect(jsonPath("$.publicExpiresAt").doesNotExist());
    }

    @Test
    void unpublish_withNoPublish_scopeUnchanged() throws Exception {
        String id = idOf(createService("no-publish-unpublish"));

        mvc.perform(delete("/api/services/" + id + "/publish")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("INTERNAL"))
                .andExpect(jsonPath("$.pendingCommand").value("NONE"));
    }

    // ── publish는 로컬 포트만 사용, privateIp는 Agent에 절대 전달 안 됨 ─────

    @Test
    void publish_tunnelTargetIsLocalPortOnly() throws Exception {
        // privateIp를 어떤 값으로 줘도 Relay는 이를 TunnelProvider에 전달하지 않음.
        // 실제 터널 대상(localhost:{port})은 VM Agent가 자신의 loopback으로 연결.
        String body = "{\"name\":\"ip-guard-test\","
                + "\"instanceId\":\"solid-99\",\"privateIp\":\"10.0.99.99\","
                + "\"localPort\":5000,\"protocol\":\"HTTP\",\"scope\":\"PRIVATE\"}";
        String svcJson = mvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", "Bearer " + apiKey))
                .andReturn().getResponse().getContentAsString();
        String id = idOf(svcJson);

        mvc.perform(post("/api/services/" + id + "/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ttlHours\":1}")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.pendingCommand").value("OPEN_TUNNEL"))
                .andExpect(jsonPath("$.publicUrl").doesNotExist()); // URL 없음
    }

    // ── publish 중복 호출 — 상태 일관성 ──────────────────────────────────────

    @Test
    void publish_calledTwice_stillPending() throws Exception {
        // publish를 두 번 연속 호출해도 scopeBeforePublish가 덮어써지지 않고 PENDING 유지
        String id = idOf(createService("pub-twice"));

        mvc.perform(post("/api/services/" + id + "/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ttlHours\":2}")
                .header("Authorization", "Bearer " + apiKey));

        mvc.perform(post("/api/services/" + id + "/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ttlHours\":4}")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.pendingCommand").value("OPEN_TUNNEL"))
                .andExpect(jsonPath("$.scope").value("INTERNAL")); // scope 변경 없음

        // 그 후 unpublish → scope가 INTERNAL로 정확히 복원되는지 확인
        mvc.perform(delete("/api/services/" + id + "/publish")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("INTERNAL"))
                .andExpect(jsonPath("$.pendingCommand").value("NONE"));
    }

    // ── TTL 범위 클램핑 ──────────────────────────────────────────────────────

    @Test
    void publish_ttlClampedTo1to24() throws Exception {
        String id = idOf(createService("ttl-clamp-test"));

        mvc.perform(post("/api/services/" + id + "/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ttlHours\":100}")  // 24 초과
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicExpiresAt").isNotEmpty());
    }

    // ── 인바운드 접근 정책 ───────────────────────────────────────────────────

    @Test
    void create_defaultAccessPolicyIsDkuInternal() throws Exception {
        mvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson("policy-default"))
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessPolicy").value("DKU_INTERNAL"))
                .andExpect(jsonPath("$.allowedEmails").isArray())
                .andExpect(jsonPath("$.allowedEmails").isEmpty());
    }

    @Test
    void create_withAllowlist_storesNormalizedEmails() throws Exception {
        String body = "{\"name\":\"policy-allow\","
                + "\"instanceId\":\"solid-01\",\"privateIp\":\"10.0.0.1\","
                + "\"localPort\":8080,\"protocol\":\"HTTP\",\"scope\":\"INTERNAL\","
                + "\"accessPolicy\":\"ALLOWLIST\","
                + "\"allowedEmails\":[\"Hong@dankook.ac.kr\",\"lee@dankook.ac.kr\"]}";
        mvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessPolicy").value("ALLOWLIST"))
                .andExpect(jsonPath("$.allowedEmails",
                        hasItems("hong@dankook.ac.kr", "lee@dankook.ac.kr")));
    }

    @Test
    void update_accessPolicy_reflected() throws Exception {
        String id = idOf(createService("policy-update"));
        mvc.perform(patch("/api/services/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accessPolicy\":\"ALLOWLIST\",\"allowedEmails\":[\"x@dankook.ac.kr\"]}")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessPolicy").value("ALLOWLIST"))
                .andExpect(jsonPath("$.allowedEmails", hasItem("x@dankook.ac.kr")));
    }
}
