package com.loresentry.authentication.domain;

/** 최초 가입 이름의 보정과 사용자가 수정한 이름의 검증 규칙을 정의한다. */
public final class DisplayNames {
    private static final String FALLBACK = "Writer";

    private DisplayNames() {}

    /**
     * 공급자 이름을 최초 표시 이름으로 보정한다. 이름이 없으면 이메일의 @ 앞부분을 같은 규칙으로 보정해 사용한다.
     *
     * @param name 공급자의 이름. null과 공백을 허용
     * @param email 공급자의 이메일. null과 @가 없는 값을 허용
     * @return 앞뒤 공백을 제거하고 최대 50 코드 포인트로 자른 이름. 이름과 이메일 앞부분이 모두 비면 "Writer"
     */
    public static String initial(String name, String email) {
        var value = truncated(name);
        if (value.isEmpty()) value = truncated(localPart(email));
        return value.isEmpty() ? FALLBACK : value;
    }

    /**
     * 사용자가 수정한 이름을 정규화하고 길이를 검증한다.
     *
     * @param name 변경할 이름
     * @return 앞뒤 공백을 제거한 이름
     * @throws InvalidDisplayName null이거나 공백 제거 후 길이가 1~50 코드 포인트 범위를 벗어난 경우
     */
    public static String edited(String name) {
        if (name == null) throw new InvalidDisplayName();
        var value = name.strip();
        if (value.isEmpty() || length(value) > 50) throw new InvalidDisplayName();
        return value;
    }

    private static String truncated(String name) {
        if (name == null) return "";
        var value = name.strip();
        return length(value) > 50 ? value.substring(0, value.offsetByCodePoints(0, 50)) : value;
    }

    private static String localPart(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        return at < 0 ? null : email.substring(0, at);
    }

    private static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    public static final class InvalidDisplayName extends RuntimeException {
        public InvalidDisplayName() {
            super("Invalid display name");
        }
    }
}
