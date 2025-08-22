package com.merbancapital.backend.controller;

import com.merbancapital.backend.service.FileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.client.RestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import java.util.Map;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import java.util.ArrayList;
import java.util.Iterator;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;


@RestController
@RequestMapping("/api/files")
@CrossOrigin(origins = "*")
public class FileController {

    @Autowired
    private FileService fileService;

    @Value("${ocr.api.url:}")
    private String ocrApiUrl;
    @Value("${ocr.api.token:}")
    private String ocrApiToken;

    // 1) Upload endpoint (called by your OCR script)
     @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(@RequestParam(value = "file", required = false) MultipartFile file, HttpServletRequest request) throws IOException {
        // Debug: inspect multipart request parts (helps when clients send wrong form-data)
        try {
            if (request instanceof MultipartHttpServletRequest) {
                MultipartHttpServletRequest mreq = (MultipartHttpServletRequest) request;
                Iterator<String> it = mreq.getFileNames();
                ArrayList<String> partNames = new ArrayList<>();
                while (it.hasNext()) {
                    String part = it.next();
                    partNames.add(part);
                    for (MultipartFile mf : mreq.getFiles(part)) {
                        System.out.println("[BACKEND] multipart part='" + part + "' filename='" + mf.getOriginalFilename() + "' size=" + mf.getSize());
                    }
                }
                System.out.println("[BACKEND] multipart parts present: " + partNames);
            } else {
                System.out.println("[BACKEND] request not multipart; content-type=" + request.getContentType());
            }
        } catch (Exception e) {
            System.out.println("[BACKEND] failed to inspect multipart request: " + e.getMessage());
        }

        if (file == null || file.isEmpty()) {
            // Return JSON explaining the problem instead of a generic 400 with empty body
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "missing file form-field 'file' or file is empty",
                    "hint", "send as multipart/form-data with key 'file' (type=File)"
            ));
        }
    // Always prefer remote OCR (application is remote-only now)
    if (ocrApiUrl != null && !ocrApiUrl.isBlank()) {
            try {
                RestTemplate rt = new RestTemplate();
                String url = ocrApiUrl.endsWith("/") ? ocrApiUrl + "api/files/upload" : ocrApiUrl + "/api/files/upload";

                MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
                ByteArrayResource bar = new ByteArrayResource(file.getBytes()) {
                    @Override public String getFilename() { return file.getOriginalFilename(); }
                };
                body.add("file", bar);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.MULTIPART_FORM_DATA);

                if (ocrApiToken != null && !ocrApiToken.isBlank()) headers.set("Authorization", "Bearer " + ocrApiToken);
                HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

                ResponseEntity<String> resp = rt.postForEntity(url, requestEntity, String.class);
                int code = resp.getStatusCode().value();
                String bodyText = resp.getBody();
                System.out.println("[BACKEND] Forwarded upload to OCR: " + url + " -> status=" + code + ", body=" + bodyText);
                if (resp.getStatusCode().is2xxSuccessful()) {
                    logUploaded(file.getOriginalFilename(), "forwarded to " + url);
                    return ResponseEntity.ok(Map.of("status", "forwarded", "ocrStatus", code, "ocrBody", bodyText));
                } else {
                    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "OCR upload failed", "status", code, "body", bodyText));
                }
            } catch (Exception e) {
                e.printStackTrace();
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Forward to OCR failed", "detail", e.getMessage()));
            }
        }

    // Fallback (no remote configured): Delegate storage to FileService temp fallback
        String stored;
        try {
            stored = fileService.store(file);
        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Upload failed.");
        }
        // stored now contains the absolute path where the file was saved
        logUploaded(file.getOriginalFilename(), stored);
        return ResponseEntity.ok("File uploaded to: " + stored);
    }

    // small helper to centralize debug logging for uploads
    private void logUploaded(String originalName, String savedPath) {
        System.out.println("[BACKEND] Received file for OCR: " + originalName + " -> " + savedPath);
    }


    // 2) List endpoint (called by frontend)
    @GetMapping("/list")
    public ResponseEntity<List<String>> listFiles() throws IOException {
        List<String> files = fileService.listIndexedFiles()
                .map(Path::getFileName)
                .map(Object::toString)
                .collect(Collectors.toList());
        return ResponseEntity.ok(files);
    }

    // 3) Download endpoint (frontend fetches file by name)
    @GetMapping("/{filename:.+}")
    public ResponseEntity<Resource> serveFile(@PathVariable String filename) throws IOException {
        Resource file = fileService.loadAsResource(filename);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.getFilename() + "\"")
                .body(file);
    }
}

/*
 * mysql -h dbaas-db-5887442-do-user-24806705-0.h.db.ondigitalocean.com -P 25060 -u doadmin -p --ssl-ca="C:\Users\Kenny\Downloads\Telegram Desktop\Bsc Info Tech\Coding\Web App development\JAVA\Springboot projects\Merbanhub temp\MerbanHub\database\ca-certificate.crt" defaultdb
Enter password: ************************
 */