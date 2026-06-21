package com.solid.connectgpu.controller;

import com.solid.connectgpu.model.User;
import com.solid.connectgpu.model.VmInfo;
import com.solid.connectgpu.port.CloudStackProvider;
import com.solid.connectgpu.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 현재 사용자가 접근 가능한 SOLID VM 목록. DNS 레코드/서비스 등록 시
 * 사설 IP·instanceId 자동 채움에 사용한다. (현재 CloudStackProvider는 모의 구현)
 */
@RestController
@RequestMapping("/api/vms")
public class VmController {

    private final CloudStackProvider cloudStack;
    private final UserService userService;

    public VmController(CloudStackProvider cloudStack, UserService userService) {
        this.cloudStack = cloudStack;
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<List<VmInfo>> list(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        User user = authenticate(auth);
        if (user == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(cloudStack.listVmsForOwner(user.getEmail()));
    }

    private User authenticate(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        return userService.findByApiKey(auth.substring(7));
    }
}
