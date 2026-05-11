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
    private final Map<String, String> ownerIndex = new ConcurrentHashMap<>(); // email → code
    private final SecureRandom random = new SecureRandom();

    public Session register(String owner, String ngrokHost, int sshPort, String otp, String jupyterToken) {
        // 같은 owner의 기존 세션 교체
        String oldCode = ownerIndex.get(owner);
        if (oldCode != null) sessions.remove(oldCode);

        String code = generateUniqueCode();
        Session session = new Session(code, owner, ngrokHost, sshPort, otp, jupyterToken);
        sessions.put(code, session);
        ownerIndex.put(owner, code);
        return session;
    }

    public Optional<Session> find(String code) {
        Session s = sessions.get(code);
        if (s == null || s.isExpired()) {
            if (s != null) ownerIndex.remove(s.getOwner());
            sessions.remove(code);
            return Optional.empty();
        }
        return Optional.of(s);
    }

    public Optional<Session> findByOwner(String owner) {
        String code = ownerIndex.get(owner);
        if (code == null) return Optional.empty();
        return find(code);
    }

    public boolean remove(String code) {
        Session s = sessions.remove(code);
        if (s != null) {
            ownerIndex.remove(s.getOwner());
            return true;
        }
        return false;
    }

    public boolean removeByOwner(String owner) {
        String code = ownerIndex.remove(owner);
        if (code == null) return false;
        sessions.remove(code);
        return true;
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
        sessions.entrySet().removeIf(e -> {
            if (e.getValue().isExpired()) {
                ownerIndex.remove(e.getValue().getOwner());
                return true;
            }
            return false;
        });
    }
}
