package com.arthur.labops.auth;

record IssuedAuthSession(AuthResponse response, String refreshToken) {
}
