package com.loresentry.authentication.adapter.in.web;

import com.loresentry.authentication.logging.AppLogger;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        // 헬스체크 줄에는 RequestLogFilter가 tags: health를 붙이고, info 줄은 Logstash가 버린다
        AppLogger.current().info("health check requested");
        return Map.of("status", "ok");
    }

    @GetMapping("/")
    public Map<String, String> root() {
        AppLogger.current().info("service root requested");
        return Map.of("service", "authentication-api");
    }
}
