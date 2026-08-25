package com.solid.connectgpu.model;

/**
 * SOLID(CloudStack) VM 인스턴스 요약 정보. DNS 레코드 값이나 서비스 등록 시
 * 사설 IP·instanceId를 학생이 직접 입력하지 않고 골라 채우도록 제공한다.
 *
 * @param instanceId  인스턴스 식별자 (예: solid-32211690)
 * @param displayName 표시 이름 (DNS 레코드의 vmName)
 * @param privateIp   VM 사설 IPv4 (예: 10.0.10.11)
 * @param state       전원 상태 (Running, Stopped 등)
 * @param account     소유 계정(학번) — 소유권 검증·표시용
 */
public record VmInfo(
        String instanceId,
        String displayName,
        String privateIp,
        String state,
        String account
) {}
