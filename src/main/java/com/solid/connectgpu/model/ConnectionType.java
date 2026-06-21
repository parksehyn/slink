package com.solid.connectgpu.model;

public enum ConnectionType {
    JUPYTER,   // Colab/외부 JupyterLab (slink connect 대상)
    HTTP,      // 일반 HTTP(S) 서비스
    SSH,       // SSH 엔드포인트
    OTHER
}
