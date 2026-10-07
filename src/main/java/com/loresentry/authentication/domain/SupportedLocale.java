package com.loresentry.authentication.domain;

import java.util.Arrays;
import java.util.Optional;

/** 계정 언어와 약관 번역이 지원하는 언어다. 외부 값은 소문자 코드 ko·en과 정확히 일치할 때만 인정한다. */
public enum SupportedLocale {
    KO("ko"),
    EN("en");

    private final String code;

    SupportedLocale(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * 외부에서 받은 언어 코드를 지원 언어로 변환한다.
     *
     * @param code 언어 코드. null 허용
     * @return 대소문자와 공백까지 정확히 일치하는 언어. 그 밖의 값은 빈 Optional
     */
    public static Optional<SupportedLocale> find(String code) {
        return Arrays.stream(values()).filter(locale -> locale.code.equals(code)).findFirst();
    }
}
