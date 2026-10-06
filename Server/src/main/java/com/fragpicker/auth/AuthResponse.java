package com.fragpicker.auth;

public record AuthResponse(String accessToken, String tokenType, long expiresIn, String refreshToken,
                           long refreshExpiresIn, UserResponse user) {
    @Override public String toString() { return "AuthResponse[tokens=REDACTED]"; }
}
