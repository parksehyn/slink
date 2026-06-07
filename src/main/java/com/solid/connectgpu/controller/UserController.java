package com.solid.connectgpu.controller;

import com.solid.connectgpu.dto.UserMeResponse;
import com.solid.connectgpu.dto.UserRegisterRequest;
import com.solid.connectgpu.dto.UserRegisterResponse;
import com.solid.connectgpu.model.User;
import com.solid.connectgpu.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody UserRegisterRequest req) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(userService.register(req));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
    }

    @GetMapping("/me")
    public ResponseEntity<UserMeResponse> me(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) return ResponseEntity.status(401).build();
        User user = userService.findByApiKey(auth.substring(7));
        if (user == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(new UserMeResponse(user.getStudentId(), user.getEmail()));
    }
}
