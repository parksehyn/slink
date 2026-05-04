package com.solid.connectgpu.service;

import com.solid.connectgpu.model.Session;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionService {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public Session register(String ngrokHost, int sshPort, String otp, String jupyterToken) {
        String code = generateUniqueCode();
        Session session = new Session(code, ngrokHost, sshPort, otp, jupyterToken);
        sessions.put(code, session);
        return session;
    }

    public Optional<Session> find(String code) {
        Session s = sessions.get(code);
        if (s == null || s.isExpired()) {
            sessions.remove(code);
            return Optional.empty();
        }
        return Optional.of(s);
    }

    public boolean remove(String code) {
        return sessions.remove(code) != null;
    }

    private String generateUniqueCode() {
        String code;
        do {
            code = String.format("%06d", random.nextInt(1_000_000));
        } while (sessions.containsKey(code));
        return code;
    }

    @Scheduled(fixedDelay = 300_000)
    public void cleanExpired() {
        sessions.entrySet().removeIf(e -> e.getValue().isExpired());
    }
}
