package com.solid.connectgpu.model;

/**
 * SOLID(CloudStack) 로그인으로 검증된 사용자 신원. slink 자체 발급 키 대신
 * 실제 CloudStack 계정에서 받아온다. {@code account}(학번)를 소유자 식별자(ownerId)로 쓴다.
 *
 * <p>{@code sessionKey}/{@code cookie}는 이후 CloudStack 호출(VM 목록·소유권 검증)에
 * 재사용하는 자격이며 외부 응답에 노출하지 않는다(모의 구현에서는 placeholder).
 *
 * @param account    CloudStack account/username (예: 학번 "32211690") — ownerId
 * @param domain     CloudStack 도메인 (예: "SW")
 * @param userId     CloudStack user id
 * @param email      이메일 (없을 수 있음)
 * @param sessionKey CloudStack API sessionkey (서버 보관, 비노출)
 * @param cookie     CloudStack JSESSIONID 쿠키 (서버 보관, 비노출)
 */
public record SolidIdentity(
        String account,
        String domain,
        String userId,
        String email,
        String sessionKey,
        String cookie
) {}
