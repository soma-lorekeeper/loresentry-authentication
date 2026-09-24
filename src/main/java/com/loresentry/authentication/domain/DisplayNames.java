package com.loresentry.authentication.domain;

/** 최초 가입 이름의 보정과 사용자가 수정한 이름의 검증 규칙을 정의한다. */
public final class DisplayNames {
    private DisplayNames() {}

    /**
     * 공급자 이름을 최초 표시 이름으로 보정한다.
     *
     * @param name 공급자의 이름. null과 공백을 허용
     * @return 앞뒤 공백을 제거하고 최대 50 코드 포인트로 자른 이름. 값이 없으면 "사용자"
     */
    public static String initial(String name) {
        if (name == null || name.isBlank()) return "사용자";
        var value = name.strip();
        return length(value) > 50 ? value.substring(0, value.offsetByCodePoints(0, 50)) : value;
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

    private static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    public static final class InvalidDisplayName extends RuntimeException {
        public InvalidDisplayName() {
            super("Invalid display name");
        }
    }
}
