package com.solid.connectgpu.port.impl;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.solid.connectgpu.model.SolidIdentity;
import com.solid.connectgpu.model.VmInfo;
import com.solid.connectgpu.port.CloudStackProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 실제 SOLID(CloudStack) 연동. {@code cloudstack.api.url} 설정 시 활성화된다.
 * CloudStack {@code login}으로 sessionkey/JSESSIONID를 받고, 이후 {@code listVirtualMachines}를
 * 해당 세션으로 호출해 VM·사설 IP·소유권을 조회한다(명세서 §4).
 *
 * <p>운영팀의 CloudStack API 접근 권한이 필요하며(외부 의존성), 자격증명 없이 빌드/데모는
 * {@link MockCloudStackProvider}로 동작한다.
 */
@Component
@Primary
@ConditionalOnProperty(name = "cloudstack.api.url")
public class SolidCloudStackProvider implements CloudStackProvider {

    private static final Logger log = LoggerFactory.getLogger(SolidCloudStackProvider.class);

    private final String apiUrl;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public SolidCloudStackProvider(@Value("${cloudstack.api.url}") String apiUrl, ObjectMapper mapper) {
        this.apiUrl = apiUrl.replaceAll("\\?$", "");
        this.mapper = mapper;
        log.info("[CLOUDSTACK] real provider enabled, api.url={}", this.apiUrl);
    }

    @Override
    public SolidIdentity login(String username, String password, String domain) {
        // CloudStack login은 HTTP POST(form-encoded)만 허용한다.
        StringBuilder form = new StringBuilder("command=login&response=json")
                .append("&username=").append(enc(username))
                .append("&password=").append(enc(password));
        if (domain != null && !domain.isBlank()) form.append("&domain=").append(enc(domain));
        try {
            HttpResponse<String> res = http.send(
                    HttpRequest.newBuilder(URI.create(apiUrl))
                            .timeout(Duration.ofSeconds(15))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode r = mapper.readTree(res.body()).path("loginresponse");
            String sessionKey = r.path("sessionkey").asText(null);
            if (res.statusCode() != 200 || sessionKey == null || sessionKey.isBlank())
                throw new IllegalStateException("CloudStack login rejected: "
                        + r.path("errortext").asText("invalid credentials"));
            String cookie = res.headers().allValues("set-cookie").stream()
                    .map(c -> c.split(";", 2)[0]).collect(Collectors.joining("; "));
            return new SolidIdentity(
                    r.path("account").asText(r.path("username").asText(username)),
                    r.path("domain").asText(domain == null ? "" : domain),
                    r.path("userid").asText(""),
                    r.path("email").asText(null),
                    sessionKey, cookie);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("CloudStack login failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<VmInfo> listVms(SolidIdentity identity) {
        // 세션이 사용자 본인 VM으로 범위를 한정하므로 account 파라미터는 불필요.
        String q = apiUrl + "?command=listVirtualMachines&listAll=true&details=nics&response=json"
                + "&sessionkey=" + enc(identity.sessionKey());
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(q)).timeout(Duration.ofSeconds(15)).GET();
            if (identity.cookie() != null && !identity.cookie().isBlank()) b.header("Cookie", identity.cookie());
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("[CLOUDSTACK] listVirtualMachines HTTP {}", res.statusCode());
                return List.of();
            }
            JsonNode vms = mapper.readTree(res.body()).path("listvirtualmachinesresponse").path("virtualmachine");
            List<VmInfo> out = new ArrayList<>();
            for (JsonNode vm : vms) {
                String ip = vm.path("nic").isArray() && vm.path("nic").size() > 0
                        ? vm.path("nic").get(0).path("ipaddress").asText("") : "";
                out.add(new VmInfo(
                        vm.path("id").asText(""),
                        vm.path("displayname").asText(vm.path("name").asText("")),
                        ip,
                        vm.path("state").asText(""),
                        vm.path("account").asText(identity.account())));
            }
            return out;
        } catch (Exception e) {
            log.warn("[CLOUDSTACK] listVirtualMachines failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public Optional<VmInfo> findVm(SolidIdentity identity, String instanceId) {
        if (instanceId == null) return Optional.empty();
        return listVms(identity).stream().filter(vm -> instanceId.equals(vm.instanceId())).findFirst();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
