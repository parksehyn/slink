package com.solid.connectgpu.model;

/**
 * 인바운드(외부→SOLID) 공개 시 접근 허용 정책.
 *
 * <p><b>주의:</b> 현재는 정책을 저장·표시만 하며 실제 접근 차단은 시행하지 않는다.
 * Quick Tunnel URL은 링크를 아는 누구나 접근 가능하다. 실제 시행에는
 * Cloudflare Access/Named Tunnel + CloudStack 계정 검증이 필요하다(예정).
 */
public enum AccessPolicy {
    DKU_INTERNAL,  // 단국대 내부 전용 (기본)
    ALLOWLIST      // 지정한 단국대 계정(이메일)만 허용
}
