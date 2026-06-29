package com.solid.connectgpu;

import com.jayway.jsonpath.JsonPath;
import com.solid.connectgpu.service.DnsRecordRegistry;
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

/**
 * 전역 유일 이름 정책 보완 테스트(예약어 차단·개수 상한·방치 회수).
 * 정책 수치를 속성으로 낮춰 결정적으로 검증한다(상한 2개, 예약어 ns/www, 회수는 직접 호출).
 */
@SpringBootTest(properties = {
        "dns.max-records-per-owner=2",
        "dns.reserved-names=ns,www",
        "dns.record.expire-days=1"
})
class DnsRecordPolicyTest {

    @Autowired WebApplicationContext context;
    @Autowired DnsRecordRegistry registry;
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

    private org.springframework.test.web.servlet.ResultActions createA(String name) throws Exception {
        return mvc.perform(post("/api/dns/records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"A\",\"name\":\"" + name + "\",\"vmId\":\"" + vmId + "\"}")
                .header("Authorization", "Bearer " + token));
    }

    @Test
    void reservedName_returns400() throws Exception {
        createA("ns")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("RESERVED_NAME"));
    }

    @Test
    void overLimit_returns429() throws Exception {
        createA("rec-1").andExpect(status().isCreated());
        createA("rec-2").andExpect(status().isCreated());
        createA("rec-3")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RECORD_LIMIT_EXCEEDED"));
    }

    @Test
    void reclaim_removesRecord() throws Exception {
        String id = JsonPath.read(createA("stale").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        // cutoff를 미래로 두면 방금 만든 레코드도 "오래됨"으로 간주되어 회수된다(시간 의존 없음).
        int reclaimed = registry.reclaimOlderThan(Instant.now().plusSeconds(60));
        org.junit.jupiter.api.Assertions.assertTrue(reclaimed >= 1);
        mvc.perform(get("/api/dns/records/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
