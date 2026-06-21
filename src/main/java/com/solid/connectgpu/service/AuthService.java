package com.solid.connectgpu.service;

import com.solid.connectgpu.model.SolidIdentity;
import com.solid.connectgpu.port.CloudStackProvider;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SOLID 세션 토큰 관리. 학번/비밀번호로 CloudStack에 로그인해 신원을 검증하고,
 * 불투명 토큰(slk-…)을 발급한다. 토큰→{@link SolidIdentity}(sessionkey 포함)를 서버에 보관하여
 * 이후 CloudStack 호출(VM·소유권)에 재사용한다. 인메모리이며 토큰 TTL은 12시간.
 *
 * <p>slink 자체 발급 키({@code sk-dku-})를 쓰던 {@code UserService}를 DNS·VM 경로에서 대체한다.
 */
@Service
public class AuthService {

    private static final long TTL_SECONDS = 12L * 3600;

    private final CloudStackProvider cloudStack;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Holder> tokens = new ConcurrentHashMap<>();

    private record Holder(SolidIdentity identity, Instant expiresAt) {}

    public AuthService(CloudStackProvider cloudStack) {
        this.cloudStack = cloudStack;
    }

    /** CloudStack 로그인 후 토큰 발급. 실패 시 예외(컨트롤러가 401 처리). */
    public String login(String username, String password, String domain) {
        SolidIdentity identity = cloudStack.login(username, password, domain);
        String token = "slk-" + randomHex();
        tokens.put(token, new Holder(identity, Instant.now().plusSeconds(TTL_SECONDS)));
        return token;
    }

    public Optional<SolidIdentity> resolve(String token) {
        if (token == null) return Optional.empty();
        Holder h = tokens.get(token);
        if (h == null) return Optional.empty();
        if (Instant.now().isAfter(h.expiresAt())) {
            tokens.remove(token);
            return Optional.empty();
        }
        return Optional.of(h.identity());
    }

    public void logout(String token) {
        if (token != null) tokens.remove(token);
    }

    private String randomHex() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
