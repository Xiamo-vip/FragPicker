package com.fragpicker.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "请输入用户名")
        @Pattern(regexp = "[A-Za-z0-9_]{3,32}", message = "用户名需要3～32位字母、数字或下划线")
        String username,
        @NotBlank(message = "请输入密码")
        @Size(min = 8, max = 64, message = "密码需要8～64个字符，UTF-8长度最多72字节")
        String password) {
    @Override
    public String toString() {
        return "RegisterRequest[credentials=REDACTED]";
    }
}
