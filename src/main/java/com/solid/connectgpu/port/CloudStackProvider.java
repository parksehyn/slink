package com.solid.connectgpu.port;

import com.solid.connectgpu.model.VmInfo;

import java.util.List;

/**
 * SOLID(Apache CloudStack, dku.kloud.zone) VM 정보 조회 포트.
 * {@link DnsProvider}/{@link TunnelProvider}와 동일하게 인터페이스로 분리한다.
 *
 * 현재는 {@code MockCloudStackProvider}가 샘플 데이터를 돌려준다.
 * 자격증명(API Key + Secret) 확보 후 {@code /client/api?command=listVirtualMachines}를
 * HMAC 서명으로 호출하는 실제 구현으로 교체한다.
 */
public interface CloudStackProvider {

    /** 해당 소유자(단국대 계정 이메일)가 접근 가능한 VM 목록을 반환한다. */
    List<VmInfo> listVmsForOwner(String ownerEmail);
}
