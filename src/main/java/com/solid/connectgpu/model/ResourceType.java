package com.solid.connectgpu.model;

/**
 * 외부 자원 유형. (수동 북마크용 {@link ConnectionType}과 별개의 신규 enum 이므로
 * 기존 {@code connection.store.file} 직렬화에 영향을 주지 않는다.)
 */
public enum ResourceType {
    COLAB_GPU,   // Google Colab GPU 런타임(JupyterLab 자동 기동 + Cloudflare Tunnel)
    JUPYTER,     // 외부 JupyterLab 서버
    HTTP_API     // 일반 HTTP/API 엔드포인트
}
