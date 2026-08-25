package com.solid.connectgpu.port;

import com.solid.connectgpu.model.SolidIdentity;
import com.solid.connectgpu.model.VmInfo;

import java.util.List;
import java.util.Optional;

/**
 * SOLID(Apache CloudStack, dku.kloud.zone) 인증·VM 조회 포트.
 * {@link DnsProvider}/{@link TunnelProvider}와 동일하게 인터페이스로 분리한다.
 *
 * <p>기본은 {@code MockCloudStackProvider}(임의 자격 허용·샘플 데이터)이며,
 * {@code cloudstack.api.url} 설정 시 {@code SolidCloudStackProvider}(실제 호출)로 교체된다.
 */
public interface CloudStackProvider {

    /** CloudStack {@code login}으로 인증하고 검증된 신원을 반환한다. 실패 시 예외. */
    SolidIdentity login(String username, String password, String domain);

    /** 해당 신원(계정=학번)이 접근 가능한 VM 목록. */
    List<VmInfo> listVms(SolidIdentity identity);

    /** 해당 신원이 소유한 단일 VM을 instanceId로 조회한다(소유권은 구현이 보장). */
    Optional<VmInfo> findVm(SolidIdentity identity, String instanceId);
}
