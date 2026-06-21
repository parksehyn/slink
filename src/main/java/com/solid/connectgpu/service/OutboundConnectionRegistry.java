package com.solid.connectgpu.service;

import com.solid.connectgpu.dto.CreateConnectionRequest;
import com.solid.connectgpu.model.ConnectionType;
import com.solid.connectgpu.model.OutboundConnection;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 아웃바운드 외부 연결 저장소(인메모리, 소유자 단위 격리).
 * 외부가 열어준 터널 주소를 등록해 두고 SOLID에서 접근(소비)하는 용도.
 */
@Service
public class OutboundConnectionRegistry {

    private final ConcurrentHashMap<String, OutboundConnection> connections = new ConcurrentHashMap<>();

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
        return conn;
    }

    public boolean delete(String id, String ownerId) {
        OutboundConnection conn = connections.get(id);
        if (conn == null || !conn.getOwnerId().equals(ownerId)) return false;
        connections.remove(id);
        return true;
    }
}
