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

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class UserApiTest {

    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    /** 고유 이메일·학번으로 등록 — 매 실행마다 충돌 없음 */
    @Test
    void register_returnsApiKey() throws Exception {
        long ts = System.nanoTime();
        String email = "apitest-" + ts + "@dankook.ac.kr";
        String studentId = "U" + Math.abs(ts % 10_000_000L);
        mvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentId + "\",\"email\":\"" + email + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiKey").value(startsWith("sk-dku-")))
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void me_withValidKey_returnsUser() throws Exception {
        long ts = System.nanoTime();
        String email = "me-test-" + ts + "@dankook.ac.kr";
        String studentId = "M" + Math.abs(ts % 10_000_000L);
        String regJson = mvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentId + "\",\"email\":\"" + email + "\"}"))
                .andReturn().getResponse().getContentAsString();
        String apiKey = JsonPath.read(regJson, "$.apiKey");

        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void me_withInvalidKey_returns401() throws Exception {
        mvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer sk-dku-invalid00000000000000000000"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_withoutAuthHeader_returns401() throws Exception {
        mvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }

    // ── 보안: 중복 등록 차단 ────────────────────────────────────────────────

    @Test
    void register_duplicateEmail_returns409() throws Exception {
        long ts = System.nanoTime();
        String email = "dup-email-" + ts + "@dankook.ac.kr";
        String body = "{\"studentId\":\"D" + Math.abs(ts % 10_000_000L) + "\",\"email\":\"" + email + "\"}";

        mvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        // 다른 학번으로 같은 이메일 재등록 시도
        String body2 = "{\"studentId\":\"D" + Math.abs((ts + 1) % 10_000_000L) + "\",\"email\":\"" + email + "\"}";
        mvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
                .andExpect(status().isConflict());
    }

    @Test
    void register_duplicateStudentId_returns409() throws Exception {
        long ts = System.nanoTime();
        String studentId = "DS" + Math.abs(ts % 10_000_000L);

        mvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentId + "\",\"email\":\"first-" + ts + "@dankook.ac.kr\"}"))
                .andExpect(status().isCreated());

        // 다른 이메일로 같은 학번 재등록 시도
        mvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentId + "\",\"email\":\"second-" + ts + "@dankook.ac.kr\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void register_duplicate_originalKeyStillValid() throws Exception {
        long ts = System.nanoTime();
        String email = "orig-key-" + ts + "@dankook.ac.kr";
        String studentId = "OK" + Math.abs(ts % 10_000_000L);
        String body = "{\"studentId\":\"" + studentId + "\",\"email\":\"" + email + "\"}";

        String regJson = mvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        String originalKey = JsonPath.read(regJson, "$.apiKey");

        // 재등록 시도 → 409
        mvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());

        // 원래 키는 여전히 유효
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + originalKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }
}
