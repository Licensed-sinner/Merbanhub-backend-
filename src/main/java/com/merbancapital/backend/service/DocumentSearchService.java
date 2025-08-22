package com.merbancapital.backend.service;

import com.merbancapital.backend.dto.SearchFilters;
import com.merbancapital.backend.dto.SearchResponse;
import com.merbancapital.backend.model.Document;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.http.HttpStatus;

import jakarta.annotation.PostConstruct;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class DocumentSearchService {

        private List<Document> documents = Collections.synchronizedList(new ArrayList<>());

        // When set, operate in remote OCR mode and call the OCR API instead of local FS
        @Value("${ocr.api.url:}")
        private String ocrApiUrl;
        @Value("${ocr.api.token:}")
        private String ocrApiToken;




        // Legacy directory constants removed (remote-only mode)

        @PostConstruct
        public void init() {
                System.out.println("[DocumentSearchService] Initializing (remote-only). ocrApiUrl=" + (ocrApiUrl == null || ocrApiUrl.isBlank() ? "(none)" : ocrApiUrl));
                listRemoteFiles();
        }

        public void scanOcrFolders() {
                // No-op: local filesystem scanning removed in remote-only configuration.
                System.out.println("[DocumentSearchService] scanOcrFolders() skipped (remote-only mode)");
        }

        /**
         * Populate documents list by calling remote OCR /api/files/list which must return a JSON array of filenames.
         */
        public void listRemoteFiles() {
                List<Document> newDocuments = new ArrayList<>();
                try {
                            RestTemplate rt = new RestTemplate();
                            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                            if (ocrApiToken != null && !ocrApiToken.isBlank()) {
                                    headers.set("Authorization", "Bearer " + ocrApiToken);
                            }
                            org.springframework.http.HttpEntity<Void> req = new org.springframework.http.HttpEntity<>(headers);

                            // Try a list of common list endpoints in case the deployed OCR uses a different path
                            String[] candidates = new String[]{
                                            "api/files/list",
                                            "files/list",
                                            "list",
                                            "api/files",
                                            "files"
                            };
                            ObjectMapper mapper = new ObjectMapper();
                            String successfulUrl = null;
                            String responseBody = null;
                            for (String c : candidates) {
                                    String url = ocrApiUrl.endsWith("/") ? ocrApiUrl + c : ocrApiUrl + "/" + c;
                                    try {
                                            ResponseEntity<String> resp = rt.exchange(url, org.springframework.http.HttpMethod.GET, req, String.class);
                                            if (resp.getStatusCode().is2xxSuccessful()) {
                                                    successfulUrl = url;
                                                    responseBody = resp.getBody();
                                                    break;
                                            } else if (resp.getStatusCode() == HttpStatus.NOT_FOUND) {
                                                    // try next candidate
                                                    continue;
                                            } else {
                                                    // log and continue trying other endpoints
                                                    System.out.println("[DocumentSearchService] Tried " + url + " -> status=" + resp.getStatusCode());
                                                    continue;
                                            }
                                    } catch (HttpClientErrorException.NotFound nf) {
                                            // endpoint not present on server, try next
                                            continue;
                                    } catch (Exception e) {
                                            System.err.println("[DocumentSearchService] Error calling " + url + " : " + e.getMessage());
                                            continue;
                                    }
                            }

                            if (successfulUrl != null && responseBody != null && !responseBody.isBlank()) {
                                    // Try parsing as simple string array first
                                    try {
                                            String[] names = mapper.readValue(responseBody, String[].class);
                                            for (String name : names) {
                                                    Document d = new Document();
                                                    d.setFileName(name);
                                                    d.setFileSize(0L);
                                                    d.setDateModified(Instant.now());
                                                    String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8.toString()).replace("+", "%20");
                                                    String root = (ocrApiUrl.endsWith("/")) ? ocrApiUrl : ocrApiUrl + "/";
                                                    d.setFilePath(root + "api/files/" + encoded);
                                                    newDocuments.add(d);
                                            }
                                    } catch (Exception ex) {
                                            // Not a plain string array, try list of objects with name/url
                                            try {
                                                    List<Map<String, Object>> objs = mapper.readValue(responseBody, new TypeReference<List<Map<String, Object>>>(){});
                                                    for (Map<String, Object> obj : objs) {
                                                            String name = null;
                                                            String url = null;
                                                            if (obj.containsKey("filename")) name = String.valueOf(obj.get("filename"));
                                                            if (obj.containsKey("fileName")) name = String.valueOf(obj.get("fileName"));
                                                            if (obj.containsKey("name")) name = String.valueOf(obj.get("name"));
                                                            if (obj.containsKey("url")) url = String.valueOf(obj.get("url"));
                                                            if (name == null && url != null) {
                                                                    // attempt to extract name from url
                                                                    try { name = Paths.get(new java.net.URI(url).getPath()).getFileName().toString(); } catch (Exception ignore) {}
                                                            }
                                                            if (name != null) {
                                                                    Document d = new Document();
                                                                    d.setFileName(name);
                                                                    d.setFileSize(0L);
                                                                    d.setDateModified(Instant.now());
                                                                    if (url != null && !url.isBlank()) d.setFilePath(url);
                                                                    else {
                                                                            String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8.toString()).replace("+", "%20");
                                                                            String root = (ocrApiUrl.endsWith("/")) ? ocrApiUrl : ocrApiUrl + "/";
                                                                            d.setFilePath(root + "api/files/" + encoded);
                                                                    }
                                                                    newDocuments.add(d);
                                                            }
                                                    }
                                            } catch (Exception ex2) {
                                                    System.err.println("[DocumentSearchService] Could not parse remote list response: " + ex2.getMessage());
                                            }
                                    }
                            } else {
                                    System.err.println("[ERROR] No remote OCR list endpoint responded successfully among candidates.");
                            }
                } catch (Exception e) {
                            System.err.println("[ERROR] Failed to list remote OCR files: " + e.getMessage());
                }
                synchronized (documents) {
                        documents.clear();
                        documents.addAll(newDocuments);
                        System.out.println("[DocumentSearchService] Loaded " + documents.size() + " docs from remote OCR");
                }
        }

        public boolean isRemote() { return ocrApiUrl != null && !ocrApiUrl.isBlank(); }

        public String getOcrApiUrl() { return ocrApiUrl; }

                // Expose token safely so other beans/controllers can attach Authorization headers
                public String getOcrApiToken() { return ocrApiToken; }

        /**
         * Remote helper: return the full remote download URL for a given filename (case-insensitive).
         */
        public Optional<String> getRemoteFileUrl(String filename) {
                if (filename == null || filename.isBlank() || !isRemote()) return Optional.empty();
                String safe = filename.trim();
                synchronized (documents) {
                        for (Document d : documents) {
                                if (d.getFileName() != null && d.getFileName().equalsIgnoreCase(safe)) {
                                        return Optional.ofNullable(d.getFilePath());
                                }
                        }
                }
                return Optional.empty();
        }

        public SearchResponse search(SearchFilters f) {
                List<Document> filtered = new ArrayList<>(documents);
                // Simple name-based filter (template matched clientName against filename)
                if (f.getClientName() != null && !f.getClientName().isBlank()) {
                        String q = f.getClientName().toLowerCase();
                        filtered = filtered.stream()
                                        .filter(d -> d.getFileName() != null && d.getFileName().toLowerCase().contains(q))
                                        .collect(Collectors.toList());
                }

                // If an account number is provided, perform numeric-only matching.
                // Extract digits from the provided filter; if there are no digits, skip account-number filtering.
                if (f.getAccountNumber() != null && !f.getAccountNumber().isBlank()) {
                        String digitsOnly = f.getAccountNumber().replaceAll("\\D+", "");
                        if (!digitsOnly.isEmpty()) {
                                final String acc = digitsOnly; // do not lowercase digits
                                filtered = filtered.stream()
                                                .filter(d -> {
                                                        if (d.getFileName() == null) return false;
                                                        // Extract digits from filename and check contains
                                                        String fileDigits = d.getFileName().replaceAll("\\D+", "");
                                                        return fileDigits.contains(acc);
                                                })
                                                .collect(Collectors.toList());
                        } // else: query had no digits -> ignore accountNumber filter
                }

                // Pagination
                int page = f.getPage() == null ? 1 : f.getPage();
                int size = f.getPageSize() == null ? 20 : f.getPageSize();
                int fromIdx = (page - 1) * size;
                int toIdx = Math.min(fromIdx + size, filtered.size());
                List<Document> pageList = fromIdx >= filtered.size() ? Collections.emptyList()
                                : filtered.subList(fromIdx, toIdx);

                return SearchResponse.builder()
                                .documents(pageList)
                                .total(filtered.size())
                                .page(page)
                                .pageSize(size)
                                .totalPages((int) Math.ceil((double) filtered.size() / size))
                                .clientName(f.getClientName() == null ? "" : f.getClientName())
                                .accountNumber(f.getAccountNumber() == null ? "" : f.getAccountNumber())
                                .department(f.getDepartment() == null ? "" : f.getDepartment())
                                .fundDateStart(f.getFundDateStart() == null ? "" : f.getFundDateStart().toString())
                                .fundDateEnd(f.getFundDateEnd() == null ? "" : f.getFundDateEnd().toString())
                                .fileExtensions(f.getFileExtensions() == null ? List.of() : f.getFileExtensions())
                                .dateModifiedStart(f.getDateModifiedStart() == null ? ""
                                                : f.getDateModifiedStart().toString())
                                .dateModifiedEnd(
                                                f.getDateModifiedEnd() == null ? "" : f.getDateModifiedEnd().toString())
                                .fileSizeMin(f.getFileSizeMin() == null ? 0L : f.getFileSizeMin())
                                .fileSizeMax(f.getFileSizeMax() == null ? 0L : f.getFileSizeMax())
                                .ocrConfidenceMin(f.getOcrConfidenceMin() == null ? 0 : f.getOcrConfidenceMin())
                                .indexStatus(f.getIndexStatus() == null ? "" : f.getIndexStatus())
                                .fullTextSearch(f.getFullTextSearch() == null ? "" : f.getFullTextSearch())
                                .sortBy(f.getSortBy() == null ? "" : f.getSortBy())
                                .sortOrder(f.getSortOrder() == null ? "" : f.getSortOrder())
                                .build();
        }

        /**
         * Find an indexed file by exact filename (case-insensitive) inside
         * fully_indexed or partially_indexed. Returns an Optional<Path> when found.
         */
        public Optional<Path> findFileInIndexedFolders(String filename) {
                if (filename == null || filename.isBlank()) return Optional.empty();
                String safeName = Paths.get(filename).getFileName().toString();

                // Remote-only: search in loaded document list
                synchronized (documents) {
                        for (Document d : documents) {
                                if (d.getFileName() != null && d.getFileName().equalsIgnoreCase(safeName)) {
                                        // filePath may be a URL; wrap as Path using just filename for downstream logic.
                                        try { return Optional.of(Paths.get(d.getFilePath())); } catch (Exception ignored) { return Optional.of(Paths.get(safeName)); }
                                }
                        }
                }
                return Optional.empty();
        }
}
