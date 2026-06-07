package com.solid.connectgpu.service;

import com.solid.connectgpu.dto.UserRegisterRequest;
import com.solid.connectgpu.dto.UserRegisterResponse;
import com.solid.connectgpu.model.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

// TODO: Replace email/studentId self-assertion with SOLID SSO or LDAP federation.
//       Until then, any student can register any email address — there is no institutional
//       proof of identity.
@Service
public class UserService {

    private final ConcurrentHashMap<String, User> usersByEmail = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> apiKeyToEmail = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> studentIdToEmail = new ConcurrentHashMap<>();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    public UserRegisterResponse register(UserRegisterRequest req) {
        if (usersByEmail.containsKey(req.email()))
            throw new IllegalArgumentException("Email already registered: " + req.email());
        if (studentIdToEmail.containsKey(req.studentId()))
            throw new IllegalArgumentException("Student ID already registered: " + req.studentId());

        String rawKey = generateApiKey();
        String hash = encoder.encode(rawKey);
        User user = new User(req.studentId(), req.email(), hash);
        usersByEmail.put(req.email(), user);
        studentIdToEmail.put(req.studentId(), req.email());
        apiKeyToEmail.put(rawKey, req.email());
        return new UserRegisterResponse(req.studentId(), req.email(), rawKey);
    }

    public User findByApiKey(String rawKey) {
        String email = apiKeyToEmail.get(rawKey);
        if (email == null) return null;
        return findByEmail(email);
    }

    public boolean verify(String email, String rawKey) {
        User user = usersByEmail.get(email);
        if (user == null || user.isExpired()) return false;
        return encoder.matches(rawKey, user.getApiKeyHash());
    }

    public User findByEmail(String email) {
        User user = usersByEmail.get(email);
        if (user == null || user.isExpired()) return null;
        return user;
    }

    private String generateApiKey() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return "sk-dku-" + HexFormat.of().formatHex(bytes);
    }
}
