package com.fairchain.example.common;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;
import java.util.function.Function;

/** 회원 · 게이트 스태프 · 운영자 로그인이 같은 규칙을 쓴다: 아이디로 찾고 BCrypt 해시와 대조, 틀리면 401 */
public final class Credentials {

    private Credentials() {}

    /**
     * @param account      아이디로 찾은 계정 (없으면 empty)
     * @param passwordHash 계정에서 해시를 꺼내는 방법
     * @param rawPassword  입력한 비밀번호
     */
    public static <T> T authenticate(Optional<T> account, Function<T, String> passwordHash, String rawPassword,
                                     BCryptPasswordEncoder encoder) {
        return account
                .filter(a -> rawPassword != null && encoder.matches(rawPassword, passwordHash.apply(a)))
                .orElseThrow(() -> ApiException.unauthorized("아이디 또는 비밀번호가 틀렸습니다"));
    }

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
