package com.loresentry.authentication.adapter.in.web;

import com.loresentry.authentication.application.port.in.AuthFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class AuthExceptionHandler {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(
            Exception failure, HttpServletRequest request) {
        if (failure instanceof org.springframework.http.converter.HttpMessageNotReadableException
                || failure instanceof org.springframework.web.bind.ServletRequestBindingException
                || failure instanceof org.springframework.web.bind.MethodArgumentNotValidException
                || failure instanceof org.springframework.beans.TypeMismatchException
                || failure instanceof org.springframework.web.HttpRequestMethodNotSupportedException
                || failure instanceof org.springframework.web.HttpMediaTypeNotSupportedException
                || failure instanceof org.springframework.web.HttpMediaTypeNotAcceptableException) {
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
