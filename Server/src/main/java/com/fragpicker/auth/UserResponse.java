package com.fragpicker.auth;

import com.fragpicker.user.UserAccount;

public record UserResponse(Long id, String username, String businessZone) {
    public static UserResponse from(UserAccount user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getBusinessZone());
    }
}
