package com.solid.connectgpu.dto;

/**
 * 표준 에러 응답 (명세서 §10). DNS 엔드포인트에 한정 적용하여 기존 API 응답 계약을 건드리지 않는다.
 * <pre>{ "success": false, "error": { "code": "INVALID_DNS_NAME", "message": "..." } }</pre>
 */
public record ApiError(boolean success, ErrorBody error) {

    public record ErrorBody(String code, String message) {}

    public static ApiError of(String code, String message) {
        return new ApiError(false, new ErrorBody(code, message));
    }
}
