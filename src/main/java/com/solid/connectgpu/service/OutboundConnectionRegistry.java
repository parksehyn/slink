package com.solid.connectgpu.service;

import tools.jackson.databind.ObjectMapper;
import com.solid.connectgpu.dto.CreateConnectionRequest;
import com.solid.connectgpu.dto.OutboundConnectionSnapshot;
import com.solid.connectgpu.model.ConnectionType;
import com.solid.connectgpu.model.OutboundConnection;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 아웃바운드 외부 연결 저장소(소유자 단위 격리).
 * 외부가 열어준 터널 주소를 등록해 두고 SOLID에서 접근(소비)하는 용도.
 * {@code connection.store.file} 설정 시 JSON 파일로 영속(기동 시 로드, 변경 시 저장).
 */
@Service
public class OutboundConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(OutboundConnectionRegistry.class);

    private final ConcurrentHashMap<String, OutboundConnection> connections = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final String storeFile;

    public OutboundConnectionRegistry(ObjectMapper mapper,
                                      @Value("${connection.store.file:}") String storeFile) {
        this.mapper = mapper;
        this.storeFile = storeFile == null ? "" : storeFile.trim();
    }

    @PostConstruct
    public void load() {
        if (storeFile.isEmpty() || !Files.exists(Path.of(storeFile))) return;
        try {
            OutboundConnectionSnapshot[] snaps = mapper.readValue(
                    Files.readAllBytes(Path.of(storeFile)), OutboundConnectionSnapshot[].class);
            for (OutboundConnectionSnapshot s : snaps) {
                OutboundConnection c = new OutboundConnection(s.id(), s.ownerId(), s.name(),
                        ConnectionType.valueOf(s.type()), s.url(), s.token(), s.note(),
                        Instant.parse(s.createdAt()));
                connections.put(c.getId(), c);
            }
            log.info("[CONN] loaded {} connections from {}", connections.size(), storeFile);
        } catch (Exception e) {
            log.warn("[CONN] failed to load store {}: {}", storeFile, e.getMessage());
        }
    }

    public List<OutboundConnection> findByOwner(String ownerId) {
        return connections.values().stream()
                .filter(c -> c.getOwnerId().equals(ownerId))
                .sorted(Comparator.comparing(OutboundConnection::getCreatedAt).reversed())
                .toList();
    }

    public Optional<OutboundConnection> findById(String id) {
        return Optional.ofNullable(connections.get(id));
    }

    public OutboundConnection create(String ownerId, CreateConnectionRequest req) {
        String name = req.name() == null ? "" : req.name().trim();
        String url = req.url() == null ? "" : req.url().trim();
        if (name.isBlank()) throw new IllegalArgumentException("Connection name is required");
        if (url.isBlank())  throw new IllegalArgumentException("Connection URL is required");

        ConnectionType type = req.type() != null ? req.type() : ConnectionType.OTHER;
        if ((type == ConnectionType.JUPYTER || type == ConnectionType.HTTP)
                && !(url.startsWith("http://") || url.startsWith("https://")))
            throw new IllegalArgumentException("URL must start with http:// or https://");

        boolean nameTaken = connections.values().stream()
                .anyMatch(c -> c.getOwnerId().equals(ownerId) && c.getName().equals(name));
        if (nameTaken) throw new IllegalArgumentException("Connection name already in use: " + name);

        String token = req.token() != null && !req.token().isBlank() ? req.token().trim() : null;
        String note  = req.note()  != null && !req.note().isBlank()  ? req.note().trim()  : null;

        OutboundConnection conn = new OutboundConnection(ownerId, name, type, url, token, note);
        connections.put(conn.getId(), conn);
        persist();
        return conn;
    }

    public boolean delete(String id, String ownerId) {
        OutboundConnection conn = connections.get(id);
        if (conn == null || !conn.getOwnerId().equals(ownerId)) return false;
        connections.remove(id);
        persist();
        return true;
    }

    private void persist() {
        if (storeFile.isEmpty()) return;
        try {
            List<OutboundConnectionSnapshot> snaps = connections.values().stream()
                    .map(c -> new OutboundConnectionSnapshot(c.getId(), c.getOwnerId(), c.getName(),
                            c.getType().name(), c.getUrl(), c.getToken(), c.getNote(),
                            c.getCreatedAt().toString()))
                    .toList();
            Path file = Path.of(storeFile);
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, mapper.writeValueAsBytes(snaps));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.warn("[CONN] failed to persist store {}: {}", storeFile, e.getMessage());
        }
    }
}
