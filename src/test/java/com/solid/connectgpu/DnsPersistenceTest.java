package com.solid.connectgpu;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** dns.store.file 설정 시 레코드가 JSON 파일로 영속되는지 검증. */
@SpringBootTest(properties = "dns.store.file=${java.io.tmpdir}/slink-dns-persist-test.json")
class DnsPersistenceTest {

    @Autowired WebApplicationContext context;
    @Value("${dns.store.file}") String storeFile;
    MockMvc mvc;
    String token;
    String vmId;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        String login = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"55667788\",\"password\":\"pw\"}"))
                .andReturn().getResponse().getContentAsString();
        token = JsonPath.read(login, "$.token");
        String vms = mvc.perform(get("/api/vms").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        vmId = JsonPath.read(vms, "$[0].instanceId");
    }

    @Test
    void createdRecord_isWrittenToStoreFile() throws Exception {
        String name = "persist-" + System.nanoTime();
        String id = JsonPath.read(mvc.perform(post("/api/dns/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"A\",\"name\":\"" + name + "\",\"vmId\":\"" + vmId + "\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");

        Path file = Path.of(storeFile);
        assertThat(Files.exists(file)).isTrue();
        String json = Files.readString(file);
        assertThat(json).contains(name).contains(id);
    }
}
