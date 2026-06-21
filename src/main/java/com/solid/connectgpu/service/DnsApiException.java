package com.solid.connectgpu.service;

/**
 * DNS API 검증/처리 실패. 명세서 §10의 에러 코드와 HTTP 상태를 함께 운반하여
 * 컨트롤러가 {@link com.solid.connectgpu.dto.ApiError} 엔벨로프로 변환한다.
 */
public class DnsApiException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    public DnsApiException(String code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String getCode()    { return code; }
    public int getHttpStatus() { return httpStatus; }
}
