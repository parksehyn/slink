package com.solid.connectgpu.port.impl;

import com.solid.connectgpu.model.SolidIdentity;
import com.solid.connectgpu.model.VmInfo;
import com.solid.connectgpu.port.CloudStackProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * CloudStack 인증·VM 조회 모의 구현. 로그인은 임의 자격을 허용하고(계정=username),
 * VM은 계정(학번)으로부터 결정적(deterministic) 샘플을 생성한다.
 * 실제 연동({@code cloudstack.api.url} 설정) 전까지 데모·테스트가 동작하도록 한다.
 */
@Component
@ConditionalOnProperty(name = "cloudstack.api.url", matchIfMissing = true)
public class MockCloudStackProvider implements CloudStackProvider {

    private static final Logger log = LoggerFactory.getLogger(MockCloudStackProvider.class);

    @Override
    public SolidIdentity login(String username, String password, String domain) {
        String account = (username == null || username.isBlank()) ? "00000000" : username.trim();
        String dom = (domain == null || domain.isBlank()) ? "ROOT" : domain.trim();
        log.info("[CLOUDSTACK-MOCK] login (accepting any credentials) account={} domain={}", account, dom);
        return new SolidIdentity(account, dom, "mock-user-" + account,
                account + "@dankook.ac.kr", "mock-session-" + account, "");
    }

    @Override
    public List<VmInfo> listVms(SolidIdentity identity) {
        String account = identity == null ? "00000000" : identity.account();
        log.info("[CLOUDSTACK-MOCK] listVirtualMachines for account={} (sample data)", account);

        int seed = Math.abs(account == null ? 0 : account.hashCode());
        int subnet = 10 + seed % 80;     // 10.0.<subnet>.x
        int base = 11 + seed % 40;       // 마지막 옥텟 시작값

        return List.of(
                new VmInfo("solid-" + account,        "demo-web", "10.0." + subnet + "." + base,       "Running", account),
                new VmInfo("solid-" + account + "-api", "demo-api", "10.0." + subnet + "." + (base + 1), "Running", account),
                new VmInfo("solid-" + account + "-db",  "demo-db",  "10.0." + subnet + "." + (base + 2), "Stopped", account)
        );
    }

    @Override
    public Optional<VmInfo> findVm(SolidIdentity identity, String instanceId) {
        if (instanceId == null) return Optional.empty();
        return listVms(identity).stream()
                .filter(vm -> vm.instanceId().equals(instanceId))
                .findFirst();
    }
}
