package com.solid.connectgpu.service;

import com.solid.connectgpu.model.RegistrationTokenKind;
import com.solid.connectgpu.model.ResourceType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 단기·단일사용 등록 토큰(rt-) 발급/소비소. 포털이 SOLID 인증으로 발급하고, 외부/VM 에이전트가
 * 이것을 영속 에이전트 토큰(아웃바운드 {@code rat-} / 인바운드 {@code dt-})으로 교환한다.
 * 외부 환경에 SOLID 비밀번호·장기 키를 저장하지 않기 위한 장치다.
 *
 * <p>{@link AuthService}의 {@code slk-} 맵과 같은 인메모리 패턴(수 분 수명이라 영속 불필요).
 * {@link #consume}은 토큰을 즉시 제거해 <b>단일 사용</b>을 보장한다.
 */
@Service
public class RegistrationTokenRegistry {

    private final SecureRandom rng = new SecureRandom();
    private final ConcurrentHashMap<String, Grant> grants = new ConcurrentHashMap<>();
    private final long defaultTtlMinutes;

    public RegistrationTokenRegistry(
            @Value("${resource.registration-token.ttl-minutes:10}") long defaultTtlMinutes) {
        this.defaultTtlMinutes = defaultTtlMinutes > 0 ? defaultTtlMinutes : 10;
    }

    /**
     * 등록 토큰이 부여하는 권한. ownerId·resourceType·name은 서버가 신뢰하는 값이며,
     * 교환 시 클라이언트 body 대신 이 grant에서 가져온다.
     */
    public record Grant(String token, String ownerId, RegistrationTokenKind kind,
                        ResourceType resourceType, String instanceId, String name, Instant expiresAt) {}

    public Grant issue(String ownerId, RegistrationTokenKind kind, ResourceType resourceType,
                       String instanceId, String name, Long ttlMinutesOverride) {
        long mins = (ttlMinutesOverride != null && ttlMinutesOverride > 0)
                ? ttlMinutesOverride : defaultTtlMinutes;
        String token = "rt-" + randomHex();
        Grant g = new Grant(token, ownerId, kind, resourceType, instanceId, name,
                Instant.now().plusSeconds(mins * 60));
        grants.put(token, g);
        return g;
    }

    /** 토큰을 소비(제거)하고 grant를 반환. 만료/미존재/이미 사용됨이면 empty. */
    public Optional<Grant> consume(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        Grant g = grants.remove(token);   // 단일 사용: 즉시 제거
        if (g == null) return Optional.empty();
        if (Instant.now().isAfter(g.expiresAt())) return Optional.empty();
        return Optional.of(g);
    }

    /** 미사용 토큰 취소(포털의 "발급 취소"). */
    public void revoke(String token) {
        if (token != null) grants.remove(token);
    }

    private String randomHex() {
        byte[] bytes = new byte[16];
        rng.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
