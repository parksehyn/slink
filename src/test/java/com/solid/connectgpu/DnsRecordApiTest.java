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
 * DNS API 통합 테스트. 인증은 SOLID 세션 토큰(/api/auth/login, Mock CloudStack은 임의 자격 허용),
 * A 레코드는 vmId 기반(서버가 Mock VM에서 사설 IP·소유권 검증)으로 만든다.
 */
@SpringBootTest
class DnsRecordApiTest {

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    String token;
    String vmId;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        long ts = System.nanoTime();
        String username = String.valueOf(10_000_000L + Math.abs(ts % 80_000_000L)); // 8자리 학번 모양
        token = login(username);
        vmId = firstVmId();
    }

    private String login(String username) throws Exception {
        String json = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.token");
    }

    private String firstVmId() throws Exception {
        String json = mvc.perform(get("/api/vms").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$[0].instanceId");
    }

    private String createA(String name) throws Exception {
        return mvc.perform(post("/api/dns/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"A\",\"name\":\"" + name + "\",\"vmId\":\"" + vmId + "\"}")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void createA_fromVmId_resolvesIpAndOwnership() throws Exception {
        mvc.perform(post("/api/dns/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"A\",\"name\":\"app1\",\"vmId\":\"" + vmId + "\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.type").value("A"))
                .andExpect(jsonPath("$.name").value("app1"))
                .andExpect(jsonPath("$.fqdn").value("app1.solid.internal"))
                .andExpect(jsonPath("$.vmId").value(vmId))
                .andExpect(jsonPath("$.vmName").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.value").value(startsWith("10.")));
    }

    @Test
    void createCname_returnsRecord() throws Exception {
        mvc.perform(post("/api/dns/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CNAME\",\"name\":\"alias\",\"value\":\"app1.solid.internal\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("CNAME"))
                .andExpect(jsonPath("$.fqdn").value("alias.solid.internal"))
                .andExpect(jsonPath("$.value").value("app1.solid.internal"));
    }

    @Test
    void createA_missingVmId_returns400() throws Exception {
        mvc.perform(post("/api/dns/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"A\",\"name\":\"novm\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void createA_unknownVm_returns404() throws Exception {
        mvc.perform(post("/api/dns/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"A\",\"name\":\"ghost\",\"vmId\":\"solid-does-not-exist\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("VM_NOT_FOUND"));
    }

    @Test
    void create_invalidName_returns400() throws Exception {
        mvc.perform(post("/api/dns/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"A\",\"name\":\"BAD NAME\",\"vmId\":\"" + vmId + "\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_DNS_NAME"));
    }

    @Test
    void create_duplicateName_returns409() throws Exception {
        createA("dup");
        mvc.perform(post("/api/dns/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"A\",\"name\":\"dup\",\"vmId\":\"" + vmId + "\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_RECORD"));
    }

    @Test
    void update_valueOutOfPrivateRange_returns400() throws Exception {
        String id = JsonPath.read(createA("rng"), "$.id");
        mvc.perform(patch("/api/dns/records/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"8.8.8.8\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_IP_RANGE"));
    }

    @Test
    void update_changesName_updatesFqdn() throws Exception {
        String id = JsonPath.read(createA("oldname"), "$.id");
        mvc.perform(patch("/api/dns/records/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"newname\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("newname"))
                .andExpect(jsonPath("$.fqdn").value("newname.solid.internal"));
    }

    @Test
    void getSingle_returnsRecord() throws Exception {
        String id = JsonPath.read(createA("single"), "$.id");
        mvc.perform(get("/api/dns/records/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("single"));
    }

    @Test
    void list_returnsOwnRecords() throws Exception {
        createA("list-a");
        createA("list-b");
        mvc.perform(get("/api/dns/records").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItems("list-a", "list-b")));
    }

    @Test
    void delete_thenGoneFromList() throws Exception {
        String id = JsonPath.read(createA("del"), "$.id");
        mvc.perform(delete("/api/dns/records/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/dns/records").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$[*].name", not(hasItem("del"))));
    }

    @Test
    void unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/dns/records"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void otherUsersRecord_notDeletable() throws Exception {
        String id = JsonPath.read(createA("mine"), "$.id");
        String otherToken = login(String.valueOf(90_000_000L + (System.nanoTime() % 9_000_000L)));
        mvc.perform(delete("/api/dns/records/" + id).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_defaultTtlIs3600() throws Exception {
        mvc.perform(post("/api/dns/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"A\",\"name\":\"ttl-default\",\"vmId\":\"" + vmId + "\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ttl").value(3600));
    }

    @Test
    void create_customTtl_stored() throws Exception {
        mvc.perform(post("/api/dns/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"A\",\"name\":\"ttl-custom\",\"vmId\":\"" + vmId + "\",\"ttl\":300}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ttl").value(300));
    }
}
