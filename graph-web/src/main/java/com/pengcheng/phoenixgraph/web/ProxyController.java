package com.pengcheng.phoenixgraph.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

/**
 * Forwards the UI's {@code /api/graphs} and {@code /api/query} calls to the
 * graph-http backend, so the browser talks to a single origin (graph-web).
 */
@RestController
@RequestMapping("/api")
public class ProxyController {

    private final RestTemplate restTemplate;
    private final String httpUrl;

    public ProxyController(RestTemplate restTemplate,
                           @Value("${phoenixgraph.http-url:http://localhost:8081}") String httpUrl) {
        this.restTemplate = restTemplate;
        this.httpUrl = httpUrl.endsWith("/") ? httpUrl.substring(0, httpUrl.length() - 1) : httpUrl;
    }

    @GetMapping("/graphs")
    public ResponseEntity<String> graphs() {
        return forward(HttpMethod.GET, "/api/v1/graphs", null);
    }

    @PostMapping("/query")
    public ResponseEntity<String> query(@RequestBody String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return forward(HttpMethod.POST, "/api/v1/query", new HttpEntity<>(body, headers));
    }

    private ResponseEntity<String> forward(HttpMethod method, String path, HttpEntity<String> entity) {
        try {
            return restTemplate.exchange(httpUrl + path, method, entity, String.class);
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(e.getResponseBodyAsString());
        } catch (Exception e) {
            return ResponseEntity.status(502)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"graph-http unreachable\"}");
        }
    }
}
