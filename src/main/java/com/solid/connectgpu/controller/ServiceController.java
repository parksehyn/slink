package com.solid.connectgpu.controller;

import com.solid.connectgpu.dto.CreateServiceRequest;
import com.solid.connectgpu.dto.PublishRequest;
import com.solid.connectgpu.dto.ServiceResponse;
import com.solid.connectgpu.dto.UpdateServiceRequest;
import com.solid.connectgpu.model.ServiceEntry;
import com.solid.connectgpu.model.User;
import com.solid.connectgpu.service.ServiceRegistry;
import com.solid.connectgpu.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/services")
public class ServiceController {

    private final ServiceRegistry registry;
    private final UserService userService;

    public ServiceController(ServiceRegistry registry, UserService userService) {
        this.registry = registry;
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<List<ServiceResponse>> list(@RequestHeader(value = "Authorization", required = false) String auth) {
        User user = authenticate(auth);
        if (user == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(
                registry.findByOwner(user.getEmail()).stream().map(this::toResponse).toList()
        );
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody CreateServiceRequest req) {
        User user = authenticate(auth);
        if (user == null) return ResponseEntity.status(401).build();
        try {
            ServiceEntry entry = registry.create(user.getEmail(), req);
            return ResponseEntity.status(201).body(toResponse(entry));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<ServiceResponse> get(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        User user = authenticate(auth);
        if (user == null) return ResponseEntity.status(401).build();
        return registry.findById(id)
                .filter(e -> e.getOwnerId().equals(user.getEmail()))
                .map(e -> ResponseEntity.ok(toResponse(e)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody UpdateServiceRequest req) {
        User user = authenticate(auth);
        if (user == null) return ResponseEntity.status(401).build();
        try {
            return registry.update(id, user.getEmail(), req)
                    .map(e -> ResponseEntity.ok(toResponse(e)))
                    .orElse(ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        User user = authenticate(auth);
        if (user == null) return ResponseEntity.status(401).build();
        return registry.delete(id, user.getEmail())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<?> publish(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody PublishRequest req) {
        User user = authenticate(auth);
        if (user == null) return ResponseEntity.status(401).build();
        return registry.publish(id, user.getEmail(), req.ttlHours())
                .map(e -> ResponseEntity.ok(toResponse(e)))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}/publish")
    public ResponseEntity<?> unpublish(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        User user = authenticate(auth);
        if (user == null) return ResponseEntity.status(401).build();
        return registry.unpublish(id, user.getEmail())
                .map(e -> ResponseEntity.ok(toResponse(e)))
                .orElse(ResponseEntity.notFound().build());
    }

    private User authenticate(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        return userService.findByApiKey(auth.substring(7));
    }

    private ServiceResponse toResponse(ServiceEntry e) {
        return new ServiceResponse(
                e.getId(),
                e.getOwnerId(),
                e.getName(),
                e.getInstanceId(),
                e.getPrivateIp(),
                e.getLocalPort(),
                e.getProtocol().name(),
                e.getScope().name(),
                e.getStatus().name(),
                e.getPendingCommand().name(),
                e.getInternalHostname(),
                e.getPublicUrl(),
                e.getPublicExpiresAt() != null ? e.getPublicExpiresAt().toString() : null,
                e.getCreatedAt().toString(),
                e.getUpdatedAt().toString()
        );
    }
}
