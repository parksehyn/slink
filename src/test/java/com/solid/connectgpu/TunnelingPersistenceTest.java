package com.solid.connectgpu;

import com.jayway.jsonpath.JsonPath;
import com.solid.connectgpu.model.OutboundConnection;
import com.solid.connectgpu.model.ServiceEntry;
import com.solid.connectgpu.port.CloudStackProvider;
import com.solid.connectgpu.port.DnsProvider;
import com.solid.connectgpu.service.OutboundConnectionRegistry;
import com.solid.connectgpu.service.ServiceRegistry;
import com.solid.connectgpu.service.VmAgentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 터널링 영속 검증: store 파일에 쓴 뒤 새 레지스트리 인스턴스로 load()해 동일 데이터가
 * 복원되는지(round-trip) 확인한다. 서비스(상태 포함)·아웃바운드 연결·VM Agent(at- 토큰) 모두.
 */
@SpringBootTest(properties = {
        "service.store.file=${java.io.tmpdir}/slink-svc-persist-test.json",
        "connection.store.file=${java.io.tmpdir}/slink-conn-persist-test.json",
        "agent.store.file=${java.io.tmpdir}/slink-agent-persist-test.json"
})
class TunnelingPersistenceTest {

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired DnsProvider dns;
    @Autowired CloudStackProvider cloudStack;
    @Autowired VmAgentRegistry agentRegistryBean;

    @Value("${service.store.file}") String serviceStore;
    @Value("${connection.store.file}") String connStore;
    @Value("${agent.store.file}") String agentStore;

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

    @Test
    void service_persistsAndReloadsWithState() throws Exception {
        String name = "persist-svc-" + System.nanoTime();
        String body = "{\"name\":\"" + name + "\",\"instanceId\":\"" + vmId + "\","
                + "\"localPort\":3000,\"protocol\":\"HTTP\",\"scope\":\"INTERNAL\"}";
        String id = JsonPath.read(mvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");

        // publish → PENDING / OPEN_TUNNEL 상태도 영속되어야 함
        mvc.perform(post("/api/services/" + id + "/publish")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ttlHours\":4}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        ServiceRegistry fresh = new ServiceRegistry(dns, agentRegistryBean, cloudStack, mapper,
                serviceStore, 0, 0);
        fresh.load();

        Optional<ServiceEntry> e = fresh.findById(id);
        assertThat(e).isPresent();
        assertThat(e.get().getName()).isEqualTo(name);
        assertThat(e.get().getStatus().name()).isEqualTo("PENDING");
        assertThat(e.get().getPendingCommand().name()).isEqualTo("OPEN_TUNNEL");
    }

    @Test
    void connection_persistsAndReloads() throws Exception {
        String name = "persist-conn-" + System.nanoTime();
        String body = "{\"name\":\"" + name + "\",\"type\":\"JUPYTER\","
                + "\"url\":\"https://xxxx.trycloudflare.com\",\"token\":\"tok123\"}";
        String id = JsonPath.read(mvc.perform(post("/api/connections")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");

        OutboundConnectionRegistry fresh = new OutboundConnectionRegistry(mapper, connStore);
        fresh.load();

        Optional<OutboundConnection> c = fresh.findById(id);
        assertThat(c).isPresent();
        assertThat(c.get().getName()).isEqualTo(name);
        assertThat(c.get().getUrl()).isEqualTo("https://xxxx.trycloudflare.com");
        assertThat(c.get().getToken()).isEqualTo("tok123");
    }

    @Test
    void agent_persistsTokenAndReloads() throws Exception {
        String reg = mvc.perform(post("/api/agents/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":\"" + vmId + "\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String agentId = JsonPath.read(reg, "$.agentId");
        String agentToken = JsonPath.read(reg, "$.agentToken");

        VmAgentRegistry fresh = new VmAgentRegistry(mapper, agentStore);
        fresh.load();

        // at- 토큰이 보존되어 재등록 없이 검증(validate)이 통과해야 한다
        assertThat(fresh.findById(agentId)).isPresent();
        assertThat(fresh.validate(agentId, agentToken)).isPresent();
        assertThat(fresh.validate(agentId, "at-wrong")).isEmpty();
    }
}
