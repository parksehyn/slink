package com.solid.connectgpu.model;

public enum ServiceScope {
    PRIVATE,   // 소유자만 확인 (주소 없음)
    INTERNAL,  // SOLID 네트워크 → 내부 DNS
    TEAM,      // 지정 팀 → 내부 DNS + 팀 권한 (MVP에서는 목록 공유 수준)
    PUBLIC     // 인터넷 → Cloudflare Tunnel
}
