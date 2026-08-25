package com.solid.connectgpu.dto;

/** 발급된 디바이스 등록 토큰(rt-). VM에서 `slink agent enroll --token <token>`으로 1회 교환한다. */
public record DeviceTokenResponse(String token, String instanceId, String expiresAt) {}
