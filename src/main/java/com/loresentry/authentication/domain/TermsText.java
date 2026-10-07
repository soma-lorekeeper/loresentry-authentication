package com.loresentry.authentication.domain;

/** 약관 버전의 한 언어 제목과 본문이다. 원문과 번역을 같은 형태로 전달한다. */
public record TermsText(SupportedLocale locale, String title, String content) {}
