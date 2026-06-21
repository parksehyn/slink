package com.solid.connectgpu.controller;

import com.solid.connectgpu.dto.CreateDnsRecordRequest;
import com.solid.connectgpu.dto.DnsRecordResponse;
import com.solid.connectgpu.dto.UpdateDnsRecordRequest;
import com.solid.connectgpu.model.DnsRecord;
import com.solid.connectgpu.model.User;
import com.solid.connectgpu.service.DnsRecordRegistry;
import com.solid.connectgpu.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/dns/records")
public class DnsRecordController {

    private final DnsRecordRegistry registry;
    private final UserService userService;

    public DnsRecordController(DnsRecordRegistry registry, UserService userService) {
        this.registry = registry;
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<List<DnsRecordResponse>> list(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        User user = authenticate(auth);
        if (user == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(
                registry.findByOwner(user.getEmail()).stream().map(this::toResponse).toList());
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody CreateDnsRecordRequest req) {
        User user = authenticate(auth);
        if (user == null) return ResponseEntity.status(401).build();
        try {
            DnsRecord record = registry.create(user.getEmail(), req);
            return ResponseEntity.status(201).body(toResponse(record));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody UpdateDnsRecordRequest req) {
        User user = authenticate(auth);
        if (user == null) return ResponseEntity.status(401).build();
        try {
            return registry.update(id, user.getEmail(), req)
                    .map(r -> ResponseEntity.ok(toResponse(r)))
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

    private User authenticate(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        return userService.findByApiKey(auth.substring(7));
    }

    private DnsRecordResponse toResponse(DnsRecord r) {
        return new DnsRecordResponse(
                r.getId(),
                r.getOwnerId(),
                r.getType().name(),
                r.getName(),
                r.getValue(),
                r.getTtl(),
                r.getCreatedAt().toString(),
                r.getUpdatedAt().toString()
        );
    }
}
