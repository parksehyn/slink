package com.solid.connectgpu.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 아웃바운드 외부 연결: 외부 서비스(예: Colab)가 터널을 열어 발급한 주소를 등록해 두고
 * SOLID에서 그 주소로 접근(소비)한다. 기존 Colab 세션(/api/session)의 일반화이며,
 * 임의의 외부 HTTP/Jupyter/SSH 엔드포인트를 포털에서 관리할 수 있게 한다.
 */
public class OutboundConnection {

    private final String id;
    private final String ownerId;      // user email
    private String name;
    private ConnectionType type;
    private String url;                // 외부가 열어준 공개 주소
    private String token;              // 선택 (예: jupyter token)
    private String note;               // 선택 메모
    private final Instant createdAt;

    public OutboundConnection(String ownerId, String name, ConnectionType type,
                              String url, String token, String note) {
        this.id = UUID.randomUUID().toString();
        this.ownerId = ownerId;
        this.name = name;
        this.type = type;
        this.url = url;
        this.token = token;
        this.note = note;
        this.createdAt = Instant.now();
    }

    public String getId()              { return id; }
    public String getOwnerId()         { return ownerId; }
    public String getName()            { return name; }
    public ConnectionType getType()    { return type; }
    public String getUrl()             { return url; }
    public String getToken()           { return token; }
    public String getNote()            { return note; }
    public Instant getCreatedAt()      { return createdAt; }
}
