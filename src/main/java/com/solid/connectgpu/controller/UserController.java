package com.solid.connectgpu.controller;

import com.solid.connectgpu.dto.UserMeResponse;
import com.solid.connectgpu.dto.UserRegisterRequest;
import com.solid.connectgpu.dto.UserRegisterResponse;
import com.solid.connectgpu.model.User;
import com.solid.connectgpu.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "User", description = "사용자 등록 API")
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "사용자 등록 및 API Key 발급")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserRegisterResponse register(@RequestBody UserRegisterRequest req) {
        return userService.register(req);
    }

    @Operation(summary = "API Key로 현재 사용자 정보 조회")
    @GetMapping("/me")
    public ResponseEntity<UserMeResponse> me(@RequestHeader("Authorization") String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) return ResponseEntity.status(401).build();
        User user = userService.findByApiKey(auth.substring(7));
        if (user == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(new UserMeResponse(user.getStudentId(), user.getEmail()));
    }
}
