package com.merbancapital.backend.controller;

import com.merbancapital.backend.dto.OcrMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/api/ocr")
@CrossOrigin(origins = "*")
public class OcrController {

    private final Logger log = LoggerFactory.getLogger(OcrController.class);
    private final RestTemplate restTemplate;

    @Value("${OCR_API_URL:http://159.203.97.98:8000}")
    private String ocrApiUrl;

    public OcrController(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder.build();
    }

    @PostMapping("/notify")
    public OcrMetadata receiveMetadata(@RequestBody OcrMetadata meta) {
        log.info("Received OCR metadata: {}", meta);
        // TODO: later, save to DB or index
        return meta;
    }

    /**
     * Proxy /api/ocr/search?q=... to the OCR service's /search endpoint.
     * Preserves optional Authorization header.
     */
    @GetMapping("/search")
    public ResponseEntity<String> search(@RequestParam("q") String q,
                                         @RequestHeader(value = "Authorization", required = false) String authorization) {
        log.debug("Proxying search request to OCR service for q={}", q);

        String url = UriComponentsBuilder.fromHttpUrl(ocrApiUrl)
                .path("/search")
                .queryParam("q", q)
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        if (authorization != null) {
            headers.set("Authorization", authorization);
        }
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

        MediaType contentType = MediaType.APPLICATION_JSON;
        if (resp.getHeaders().getContentType() != null) {
            contentType = resp.getHeaders().getContentType();
        }

        return ResponseEntity.status(resp.getStatusCode())
                .contentType(contentType)
                .body(resp.getBody());
    }
}
