package com.solid.connectgpu.port.impl;

import com.solid.connectgpu.model.VmInfo;
import com.solid.connectgpu.port.CloudStackProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CloudStack VM 조회 모의 구현. 소유자 이메일로부터 결정적(deterministic) 샘플 VM을 생성한다.
 * 실제 연동 전까지 포털의 VM 선택 드롭다운이 동작하도록 한다.
 */
@Component
public class MockCloudStackProvider implements CloudStackProvider {

    private static final Logger log = LoggerFactory.getLogger(MockCloudStackProvider.class);

    @Override
    public List<VmInfo> listVmsForOwner(String ownerEmail) {
        log.info("[CLOUDSTACK-MOCK] Would call listVirtualMachines for {} (returning sample data)", ownerEmail);

        int seed = Math.abs(ownerEmail == null ? 0 : ownerEmail.hashCode());
        String sid = String.format("%08d", seed % 100_000_000); // 8자리 학번 모양
        int subnet = 10 + seed % 80;     // 10.0.<subnet>.x
        int base = 11 + seed % 40;       // 마지막 옥텟 시작값

        return List.of(
                new VmInfo("solid-" + sid,        "demo-web", "10.0." + subnet + "." + base,       "Running"),
                new VmInfo("solid-" + sid + "-api", "demo-api", "10.0." + subnet + "." + (base + 1), "Running"),
                new VmInfo("solid-" + sid + "-db",  "demo-db",  "10.0." + subnet + "." + (base + 2), "Stopped")
        );
    }
}
