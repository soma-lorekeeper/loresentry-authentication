package com.loresentry.authentication.adapter.in.web;

import com.loresentry.authentication.application.port.in.AuthFailure;
import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.logging.LogField;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class AuthExceptionHandler {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(
            Exception failure, HttpServletRequest request) {
        // 처리기가 없는 경로. 아래 그물이 이것까지 삼키면 경로 오류가 500 INTERNAL_ERROR 와 스택 로그가 된다
        if (failure instanceof org.springframework.web.servlet.resource.NoResourceFoundException
                || failure instanceof org.springframework.web.servlet.NoHandlerFoundException) {
            return ErrorResponses.response(new AuthFailure(AuthFailure.Reason.NOT_FOUND), request);
        }
        if (failure instanceof org.springframework.http.converter.HttpMessageNotReadableException
                || failure instanceof org.springframework.web.bind.ServletRequestBindingException
                || failure instanceof org.springframework.web.bind.MethodArgumentNotValidException
                || failure instanceof org.springframework.beans.TypeMismatchException
                || failure instanceof org.springframework.web.HttpRequestMethodNotSupportedException
                || failure instanceof org.springframework.web.HttpMediaTypeNotSupportedException
                || failure instanceof org.springframework.web.HttpMediaTypeNotAcceptableException) {
            // 400 INVALID_REQUEST의 원인 줄. 어떤 프레임워크 검증에 걸렸는지 클래스 이름만 남긴다(메시지에는 요청 값이 섞인다)
            AppLogger.current()
                    .warn("request rejected", LogField.errorType(failure.getClass().getName()));
            return ErrorResponses.response(
                    new AuthFailure(
                            AuthFailure.Reason.INVALID_REQUEST,
                            ErrorResponses.callback(request)
                                    ? AuthFailure.Consumption.NOT_CONSUMED
                                    : null),
                    request);
        }
        return ErrorResponses.response(failure, request);
    }
}
