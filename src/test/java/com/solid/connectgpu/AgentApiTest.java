package com.solid.connectgpu;

import com.jayway.jsonpath.JsonPath;
import com.solid.connectgpu.service.ServiceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class AgentApiTest {

    @Autowired WebApplicationContext context;
    @Autowired ServiceRegistry serviceRegistry;

    MockMvc mvc;
    String email;
    String apiKey;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        long ts = System.nanoTime();
        email = "agent-test-" + ts + "@dankook.ac.kr";
        String studentId = "G" + Math.abs(ts % 10_000_000L);
        String regJson = mvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentId + "\",\"email\":\"" + email + "\"}"))
                .andReturn().getResponse().getContentAsString();
        apiKey = JsonPath.read(regJson, "$.apiKey");
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    /** 이 테스트 사용자의 API Key로 agent를 등록한다. */
    private String registerAgent(String instanceId) throws Exception {
        return mvc.perform(post("/api/agents/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":\"" + instanceId + "\"}")
                        .header("Authorization", "Bearer " + apiKey))
                .andReturn().getResponse().getContentAsString();
    }

    /** 별도 사용자 계정을 만들고 그 사용자로 agent를 등록한다. */
    private String[] registerSecondUserAndAgent(String instanceId) throws Exception {
        long ts = System.nanoTime();
        String email2 = "agent2-" + ts + "@dankook.ac.kr";
        String regJson2 = mvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"B" + Math.abs(ts % 10_000_000L) + "\","
                                + "\"email\":\"" + email2 + "\"}"))
                .andReturn().getResponse().getContentAsString();
        String apiKey2 = JsonPath.read(regJson2, "$.apiKey");

        String agentJson2 = mvc.perform(post("/api/agents/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":\"" + instanceId + "\"}")
                        .header("Authorization", "Bearer " + apiKey2))
                .andReturn().getResponse().getContentAsString();
        return new String[]{apiKey2,
                JsonPath.read(agentJson2, "$.agentId"),
                JsonPath.read(agentJson2, "$.agentToken")};
    }

    private String createSvc(String name, String instanceId, int port) throws Exception {
        return mvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\","
                                + "\"instanceId\":\"" + instanceId + "\","
                                + "\"privateIp\":\"10.0.5.1\","
                                + "\"localPort\":" + port + ","
                                + "\"protocol\":\"HTTP\","
                                + "\"scope\":\"INTERNAL\"}")
                        .header("Authorization", "Bearer " + apiKey))
                .andReturn().getResponse().getContentAsString();
    }

    // ── 1. 에이전트 등록 ──────────────────────────────────────────────────────

    @Test
    void agentRegister_returnsAgentIdAndToken() throws Exception {
        mvc.perform(post("/api/agents/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":\"solid-reg-01\"}")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.agentId").isNotEmpty())
                .andExpect(jsonPath("$.agentToken").isNotEmpty())
                .andExpect(jsonPath("$.instanceId").value("solid-reg-01"));
    }

    @Test
    void agentRegister_withoutAuth_returns401() throws Exception {
        mvc.perform(post("/api/agents/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":\"solid-reg-unauth\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void agentRegister_missingInstanceId_returns400() throws Exception {
        mvc.perform(post("/api/agents/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":\"\"}")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isBadRequest());
    }

    // ── 2. heartbeat에서 OPEN_TUNNEL 명령 반환 ────────────────────────────────

    @Test
    void heartbeat_afterPublish_returnsOpenTunnelCommand() throws Exception {
        String svcJson = createSvc("hb-pub", "solid-hb-01", 4000);
        String svcId = JsonPath.read(svcJson, "$.id");

        String agentJson = registerAgent("solid-hb-01");
        String agentId   = JsonPath.read(agentJson, "$.agentId");
        String agentToken = JsonPath.read(agentJson, "$.agentToken");

        mvc.perform(post("/api/services/" + svcId + "/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ttlHours\":1}")
                .header("Authorization", "Bearer " + apiKey));

        mvc.perform(post("/api/agents/" + agentId + "/heartbeat")
                        .header("X-Agent-Token", agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commands[0].serviceId").value(svcId))
                .andExpect(jsonPath("$.commands[0].action").value("OPEN_TUNNEL"))
                .andExpect(jsonPath("$.commands[0].localPort").value(4000));
    }

    @Test
    void heartbeat_noCommands_returnsEmptyList() throws Exception {
        createSvc("hb-idle", "solid-hb-02", 5000);  // not published

        String agentJson = registerAgent("solid-hb-02");
        String agentId   = JsonPath.read(agentJson, "$.agentId");
        String agentToken = JsonPath.read(agentJson, "$.agentToken");

        mvc.perform(post("/api/agents/" + agentId + "/heartbeat")
                        .header("X-Agent-Token", agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commands").isEmpty());
    }

    @Test
    void heartbeat_wrongToken_returns401() throws Exception {
        String agentJson = registerAgent("solid-hb-auth");
        String agentId   = JsonPath.read(agentJson, "$.agentId");

        mvc.perform(post("/api/agents/" + agentId + "/heartbeat")
                        .header("X-Agent-Token", "wrong-token"))
                .andExpect(status().isUnauthorized());
    }

    // ── P1: 다른 instanceId로 등록된 agent는 서비스 명령을 볼 수 없음 ─────────

    @Test
    void heartbeat_fromDifferentInstance_returnsNoCommands() throws Exception {
        String svcJson = createSvc("hbsec-svc", "solid-hbsec-01", 5500);
        String svcId = JsonPath.read(svcJson, "$.id");

        mvc.perform(post("/api/services/" + svcId + "/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ttlHours\":1}")
                .header("Authorization", "Bearer " + apiKey));

        // 다른 instanceId로 등록된 agent
        String agentJson = registerAgent("solid-hbsec-99");
        String agentId   = JsonPath.read(agentJson, "$.agentId");
        String agentToken = JsonPath.read(agentJson, "$.agentToken");

        mvc.perform(post("/api/agents/" + agentId + "/heartbeat")
                        .header("X-Agent-Token", agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commands").isEmpty());
    }

    // ── P1: 다른 사용자의 서비스 명령은 같은 instanceId라도 보이지 않음 ────────

    @Test
    void heartbeat_doesNotLeakCommandsAcrossUsers() throws Exception {
        // User1이 solid-leak-01에 서비스를 등록하고 publish
        String svcJson = createSvc("leak-svc", "solid-leak-01", 5600);
        String svcId = JsonPath.read(svcJson, "$.id");

        mvc.perform(post("/api/services/" + svcId + "/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ttlHours\":1}")
                .header("Authorization", "Bearer " + apiKey));

        // User2가 동일한 instanceId(solid-leak-01)로 agent 등록
        String[] user2 = registerSecondUserAndAgent("solid-leak-01");
        String agentId2 = user2[1], agentToken2 = user2[2];

        // User2의 agent는 User1의 명령을 볼 수 없어야 함
        mvc.perform(post("/api/agents/" + agentId2 + "/heartbeat")
                        .header("X-Agent-Token", agentToken2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commands").isEmpty());
    }

    // ── 3. TUNNEL_READY 보고 → 서비스가 PUBLIC으로 전환 ─────────────────────

    @Test
    void report_tunnelReady_serviceBecomesPublic() throws Exception {
        String svcJson = createSvc("ready-svc", "solid-rd-01", 6000);
        String svcId   = JsonPath.read(svcJson, "$.id");

        String agentJson = registerAgent("solid-rd-01");
        String agentId   = JsonPath.read(agentJson, "$.agentId");
        String agentToken = JsonPath.read(agentJson, "$.agentToken");

        mvc.perform(post("/api/services/" + svcId + "/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ttlHours\":1}")
                .header("Authorization", "Bearer " + apiKey));

        mvc.perform(post("/api/agents/" + agentId + "/report")
                        .header("X-Agent-Token", agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceId\":\"" + svcId + "\","
                                + "\"event\":\"TUNNEL_READY\","
                                + "\"publicUrl\":\"https://ready-test.trycloudflare.com\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/services/" + svcId)
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("PUBLIC"))
                .andExpect(jsonPath("$.status").value("ONLINE"))
                .andExpect(jsonPath("$.publicUrl").value("https://ready-test.trycloudflare.com"))
                .andExpect(jsonPath("$.pendingCommand").value("NONE"));
    }

    // ── P1: 다른 instanceId의 agent가 TUNNEL_READY를 보고해도 무시됨 ──────────

    @Test
    void report_fromWrongInstance_isIgnored() throws Exception {
        String svcJson = createSvc("sec-svc", "solid-sec-01", 6100);
        String svcId = JsonPath.read(svcJson, "$.id");

        mvc.perform(post("/api/services/" + svcId + "/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ttlHours\":1}")
                .header("Authorization", "Bearer " + apiKey));

        // 다른 instanceId로 등록된 agent
        String agentJson = registerAgent("solid-sec-99");
        String agentId   = JsonPath.read(agentJson, "$.agentId");
        String agentToken = JsonPath.read(agentJson, "$.agentToken");

        // 다른 서비스에 TUNNEL_READY 보고 시도 (instanceId 불일치)
        mvc.perform(post("/api/agents/" + agentId + "/report")
                        .header("X-Agent-Token", agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceId\":\"" + svcId + "\","
                                + "\"event\":\"TUNNEL_READY\","
                                + "\"publicUrl\":\"https://malicious.trycloudflare.com\"}"))
                .andExpect(status().isOk()); // 200 반환하지만 실제 변경 없음

        // 서비스는 여전히 PENDING 상태, PUBLIC이 아님
        mvc.perform(get("/api/services/" + svcId)
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("INTERNAL"))
                .andExpect(jsonPath("$.pendingCommand").value("OPEN_TUNNEL"));
    }

    // ── 4. unpublish → CLOSE_TUNNEL, TUNNEL_STOPPED → scope 복원 ────────────

    @Test
    void report_tunnelStopped_scopeRestored() throws Exception {
        String svcJson = createSvc("stop-svc", "solid-st-01", 7000);
        String svcId   = JsonPath.read(svcJson, "$.id");

        String agentJson = registerAgent("solid-st-01");
        String agentId   = JsonPath.read(agentJson, "$.agentId");
        String agentToken = JsonPath.read(agentJson, "$.agentToken");

        mvc.perform(post("/api/services/" + svcId + "/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ttlHours\":1}")
                .header("Authorization", "Bearer " + apiKey));

        mvc.perform(post("/api/agents/" + agentId + "/report")
                .header("X-Agent-Token", agentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"serviceId\":\"" + svcId + "\","
                        + "\"event\":\"TUNNEL_READY\","
                        + "\"publicUrl\":\"https://stop-test.trycloudflare.com\"}"));

        mvc.perform(delete("/api/services/" + svcId + "/publish")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(jsonPath("$.pendingCommand").value("CLOSE_TUNNEL"));

        mvc.perform(post("/api/agents/" + agentId + "/report")
                        .header("X-Agent-Token", agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceId\":\"" + svcId + "\",\"event\":\"TUNNEL_STOPPED\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/services/" + svcId)
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("INTERNAL"))
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.publicUrl").doesNotExist())
                .andExpect(jsonPath("$.pendingCommand").value("NONE"));
    }

    // ── 5. TUNNEL_FAILED → scope 복원 + OFFLINE ──────────────────────────────

    @Test
    void report_tunnelFailed_scopeRestoredAndOffline() throws Exception {
        String svcJson = createSvc("fail-svc", "solid-fl-01", 8000);
        String svcId   = JsonPath.read(svcJson, "$.id");

        String agentJson = registerAgent("solid-fl-01");
        String agentId   = JsonPath.read(agentJson, "$.agentId");
        String agentToken = JsonPath.read(agentJson, "$.agentToken");

        mvc.perform(post("/api/services/" + svcId + "/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ttlHours\":1}")
                .header("Authorization", "Bearer " + apiKey));

        mvc.perform(post("/api/agents/" + agentId + "/report")
                        .header("X-Agent-Token", agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceId\":\"" + svcId + "\","
                                + "\"event\":\"TUNNEL_FAILED\","
                                + "\"reason\":\"cloudflared not found\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/services/" + svcId)
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("INTERNAL"))
                .andExpect(jsonPath("$.status").value("OFFLINE"))
                .andExpect(jsonPath("$.pendingCommand").value("NONE"))
                .andExpect(jsonPath("$.publicUrl").doesNotExist());
    }

    // ── 6. TTL 만료 시 에이전트가 살아있으면 CLOSE_TUNNEL 명령 생성 ──────────

    @Test
    void cleanExpired_withAliveAgent_setsCloseTunnelCommand() throws Exception {
        String svcJson = createSvc("ttl-svc", "solid-ttl-01", 9000);
        String svcId   = JsonPath.read(svcJson, "$.id");

        String agentJson = registerAgent("solid-ttl-01");
        String agentId   = JsonPath.read(agentJson, "$.agentId");
        String agentToken = JsonPath.read(agentJson, "$.agentToken");

        mvc.perform(post("/api/services/" + svcId + "/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ttlHours\":1}")
                .header("Authorization", "Bearer " + apiKey));

        mvc.perform(post("/api/agents/" + agentId + "/report")
                .header("X-Agent-Token", agentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"serviceId\":\"" + svcId + "\","
                        + "\"event\":\"TUNNEL_READY\","
                        + "\"publicUrl\":\"https://ttl-test.trycloudflare.com\"}"));

        mvc.perform(post("/api/agents/" + agentId + "/heartbeat")
                .header("X-Agent-Token", agentToken));

        serviceRegistry.findById(svcId)
                .ifPresent(e -> e.setPublicExpiresAt(Instant.now().minusSeconds(1)));
        serviceRegistry.cleanExpiredPublic();

        mvc.perform(get("/api/services/" + svcId)
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingCommand").value("CLOSE_TUNNEL"));
    }

    // ── P1: 삭제된 서비스의 orphan close는 같은 owner에게만 전달됨 ────────────

    @Test
    void orphanClose_fromDifferentOwner_isNotDelivered() throws Exception {
        // User A (this.apiKey)가 solid-orph-01에 서비스 등록 → 터널 열기 → 삭제
        String svcJson = createSvc("orph-svc", "solid-orph-01", 11000);
        String svcId   = JsonPath.read(svcJson, "$.id");

        String agentJsonA  = registerAgent("solid-orph-01");
        String agentIdA    = JsonPath.read(agentJsonA, "$.agentId");
        String agentTokenA = JsonPath.read(agentJsonA, "$.agentToken");

        mvc.perform(post("/api/services/" + svcId + "/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ttlHours\":1}")
                .header("Authorization", "Bearer " + apiKey));

        mvc.perform(post("/api/agents/" + agentIdA + "/report")
                .header("X-Agent-Token", agentTokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"serviceId\":\"" + svcId + "\","
                        + "\"event\":\"TUNNEL_READY\","
                        + "\"publicUrl\":\"https://orph-test.trycloudflare.com\"}"));

        // 서비스 삭제 → User A 소유 orphan CLOSE_TUNNEL이 "solid-orph-01|user-A-email"에 저장됨
        mvc.perform(delete("/api/services/" + svcId)
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isNoContent());

        // User B가 동일한 instanceId(solid-orph-01)로 agent 등록
        String[] user2     = registerSecondUserAndAgent("solid-orph-01");
        String agentId2    = user2[1];
        String agentToken2 = user2[2];

        // User B의 heartbeat: User A의 orphan close 명령이 보이면 안 됨
        mvc.perform(post("/api/agents/" + agentId2 + "/heartbeat")
                        .header("X-Agent-Token", agentToken2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commands").isEmpty());
    }

    // ── P2: PUBLIC 서비스 삭제 시 agent에 CLOSE_TUNNEL 명령이 전달됨 ──────────

    @Test
    void delete_withActiveTunnel_addsOrphanCloseToHeartbeat() throws Exception {
        String svcJson = createSvc("del-tunnel", "solid-del-01", 10000);
        String svcId   = JsonPath.read(svcJson, "$.id");

        String agentJson = registerAgent("solid-del-01");
        String agentId   = JsonPath.read(agentJson, "$.agentId");
        String agentToken = JsonPath.read(agentJson, "$.agentToken");

        mvc.perform(post("/api/services/" + svcId + "/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ttlHours\":1}")
                .header("Authorization", "Bearer " + apiKey));

        // Agent opens tunnel → PUBLIC
        mvc.perform(post("/api/agents/" + agentId + "/report")
                .header("X-Agent-Token", agentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"serviceId\":\"" + svcId + "\","
                        + "\"event\":\"TUNNEL_READY\","
                        + "\"publicUrl\":\"https://del-test.trycloudflare.com\"}"));

        // Delete the service while tunnel is active
        mvc.perform(delete("/api/services/" + svcId)
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isNoContent());

        // Next heartbeat must contain CLOSE_TUNNEL for the deleted service
        mvc.perform(post("/api/agents/" + agentId + "/heartbeat")
                        .header("X-Agent-Token", agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commands[0].serviceId").value(svcId))
                .andExpect(jsonPath("$.commands[0].action").value("CLOSE_TUNNEL"));
    }
}
