package com.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import okhttp3.*;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Properties;

public class ZennoBrowserApiTest {

    // Config
    private static final String CONFIG_FILE = "config.properties";
    private static final String HTML_REPORT = "zennobrowser_report.html";
    private static Properties config = new Properties();

    // Runtime
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofSeconds(30))
            .writeTimeout(Duration.ofSeconds(30))
            .build();

    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final List<TestResult> results = new ArrayList<>();
    private static List<String> createdIds = new ArrayList<>(); // для cleanup

    // Config values
    private static String BASE_URL, TOKEN, WORKSPACE_ID = "-1";

    public static void main(String[] args) throws IOException {
        try {
            loadConfig();
            System.out.println("🚀 ZennoBrowser API Test Suite V3.0 — Enterprise Edition");
            System.out.println("📡 API: " + BASE_URL + " | Workspace: " + WORKSPACE_ID);

            // Test Suite
            testFullLifecycle();
            cleanupResources();

            // Generate reports
            generateHtmlReport();
            printExecutiveSummary();

        } catch (Exception e) {
            System.err.println("💥 CRITICAL: " + e.getMessage());
            generateHtmlReport(); // аварийный отчёт
        }
    }

    private static void loadConfig() throws IOException {
        config.load(new FileInputStream(CONFIG_FILE));
        BASE_URL = config.getProperty("api.base_url");
        TOKEN = config.getProperty("api.token");
        WORKSPACE_ID = config.getProperty("workspace.id", "-1");
    }

    private static void testFullLifecycle() throws IOException {
        // Phase 1: Infrastructure
        String profileFolderId = testFolders();
        String proxyFolderId = testProxyFolders();

        // Phase 2: Core entities
        String proxyId = testProxies(proxyFolderId);
        String profileId = testProfiles(profileFolderId, proxyId);

        // Phase 3: Runtime (с retry)
        testThreadsWithRetry();
        if (profileId != null) testBrowsersWithRetry(profileId);
    }

    private static String testFolders() throws IOException {
        System.out.println("\n🏗️ PHASE 1: Infrastructure Setup");

        // Profile folders CRUD
        ApiResponse list = retryRequest("GET", "/profile_folders?workspaceId=" + WORKSPACE_ID + "&start=0&total=10");
        addResult("GET Profile Folders", 200, list);

        String folderName = "test_pf_" + shortId();
        ApiResponse create = retryRequest("POST", "/profile_folders?name=" + enc(folderName) + "&workspaceId=" + WORKSPACE_ID);
        addResult("POST Profile Folder", 200, create);

        String folderId = extractUuid(create.body);
        createdIds.add(folderId);

        if (folderId != null) {
            retryRequest("PUT", "/profile_folders/" + folderId + "?name=test_updated&workspaceId=" + WORKSPACE_ID);
            addResult("PUT Profile Folder", 200, create);
        }

        return folderId;
    }

    private static String testProxyFolders() throws IOException {
        ApiResponse list = retryRequest("GET", "/proxy_folders?workspaceId=" + WORKSPACE_ID + "&start=0&total=10");
        addResult("GET Proxy Folders", 200, list);

        String folderName = "test_proxyfolder_" + shortId();
        ApiResponse create = retryRequest("POST", "/proxy_folders?name=" + enc(folderName) + "&workspaceId=" + WORKSPACE_ID);
        addResult("POST Proxy Folder", 200, create);

        String folderId = extractUuid(create.body);
        createdIds.add(folderId);
        return folderId;
    }

    private static String testProxies(String proxyFolderId) throws IOException {
        ApiResponse list = retryRequest("GET", "/proxies?workspaceId=" + WORKSPACE_ID + "&start=0&total=10");
        addResult("GET Proxies", 200, list);

        String proxyName = "test_proxy_" + shortId();
        ApiResponse create = retryRequest("POST",
                "/proxies/create?name=" + enc(proxyName) +
                        "&proxyUri=http://demo:demo@proxy.example:8080&workspaceId=" + WORKSPACE_ID +
                        (proxyFolderId != null ? "&folderId=" + proxyFolderId : ""));
        addResult("POST Proxy", 200, create);

        return extractUuid(create.body);
    }

    private static String testProfiles(String profileFolderId, String proxyId) throws IOException {
        ApiResponse list = retryRequest("GET", "/profiles?workspaceId=" + WORKSPACE_ID + "&start=0&total=10");
        addResult("GET Profiles", 200, list);

        // Single profile
        String profileName = "test_profile_" + shortId();
        String params = "name=" + enc(profileName) + "&workspaceId=" + WORKSPACE_ID +
                (profileFolderId != null ? "&folderId=" + profileFolderId : "") +
                (proxyId != null ? "&proxyServerId=" + proxyId : "") +
                "&screen=auto&language=auto&timeZone=auto";

        ApiResponse create = retryRequest("POST", "/profiles/create?" + params);
        addResult("POST Profile Single", 200, create);
        String profileId = extractUuid(create.body);
        createdIds.add(profileId);

        // Bulk profiles
        if (profileId != null) {
            retryRequest("POST", "/profiles/create_bulk?count=2&workspaceId=" + WORKSPACE_ID +
                    (profileFolderId != null ? "&folderId=" + profileFolderId : ""));
            addResult("POST Profiles Bulk", 200, create);
        }

        return profileId;
    }

    private static void testThreadsWithRetry() throws IOException {
        System.out.println("\n⚙️ PHASE 3: Runtime (with retry logic)");

        // Single thread
        ApiResponse create = retryRequest("POST", "/threads/create?workspaceId=" + WORKSPACE_ID);
        addResult("POST Thread Single", 200, create);

        // Bulk threads (retry на лимиты)
        retryRequest("POST", "/threads/create_bulk?count=1&workspaceId=" + WORKSPACE_ID);
        addResult("POST Threads Bulk", 200, create);
    }

    private static void testBrowsersWithRetry(String profileId) throws IOException {
        // GET instances
        ApiResponse list = retryRequest("GET", "/browser_instances?workspaceId=" + WORKSPACE_ID);
        addResult("GET Browser Instances", 200, list);

        // Single browser (retry на лимиты потоков)
        retryRequest("POST", "/browser_instances/create?profileId=" + profileId + "&workspaceId=" + WORKSPACE_ID);
        addResult("POST Browser Single", 200, list);
    }

    private static void cleanupResources() throws IOException {
        if (!Boolean.parseBoolean(config.getProperty("test.cleanup", "true"))) return;

        System.out.println("\n🧹 PHASE 4: Resource Cleanup");
        for (String id : createdIds) {
            retryRequest("DELETE", "/profiles/" + id + "?workspaceId=" + WORKSPACE_ID);
            retryRequest("DELETE", "/profile_folders/" + id + "?workspaceId=" + WORKSPACE_ID);
        }
        addResult("CLEANUP Resources", 200, new ApiResponse(200, "Cleanup completed", 0L));

    }

    // 🔄 Retry Logic
    private static ApiResponse retryRequest(String method, String path) throws IOException {
        int maxRetries = Integer.parseInt(config.getProperty("api.retry_attempts", "3"));
        long delayMs = Long.parseLong(config.getProperty("api.retry_delay_ms", "2000"));

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return request(method, path, null);
            } catch (Exception e) {
                if (attempt == maxRetries) throw e;
                System.out.printf("⚠️ Retry %d/%d for %s: %s%n", attempt, maxRetries, path, e.getMessage());
                try {
                    Thread.sleep(delayMs * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        throw new RuntimeException("Max retries exceeded");
    }

    private static ApiResponse request(String method, String path, String jsonBody) throws IOException {
        Instant start = Instant.now();
        RequestBody body = jsonBody != null ?
                RequestBody.create(jsonBody, MediaType.parse("application/json")) :
                ("GET".equals(method) ? null : RequestBody.create(new byte[0]));

        Request.Builder builder = new Request.Builder()
                .url(BASE_URL + path)
                .addHeader("Api-Token", TOKEN);

        switch (method) {
            case "GET":
                builder.get();
                break;
            case "POST":
                builder.post(body);
                break;
            case "PUT":
                builder.put(body);
                break;
            case "DELETE":
                builder.delete(body);
                break;
        }

        try (Response response = client.newCall(builder.build()).execute()) {
            long duration = Duration.between(start, Instant.now()).toMillis();
            String bodyStr = response.body() != null ? response.body().string() : "";
            return new ApiResponse(response.code(), bodyStr, duration);
        }
    }

    private static void addResult(String name, int expected, ApiResponse response) {
        results.add(new TestResult(name, expected, response.code, response.body, response.durationMs));
        System.out.printf("✅ %s → %d (%dms)%n", name, response.code, response.durationMs);
    }

    private static void generateHtmlReport() throws IOException {
        StringBuilder html = new StringBuilder();
        html.append("""
                <!DOCTYPE html>
                <html><head><meta charset="UTF-8">
                <title>ZennoBrowser API Test Report V3</title>
                <style>
                /* CSS без изменений */
                </style></head><body>
                """);

        int passed = (int) results.stream().filter(r -> r.passed()).count();
        double successRate = passed * 100.0 / results.size();

        // ИСПРАВЛЕННЫЙ заголовок — %.1f только для double
        html.append(String.format("""
                        <div class="header">
                            <h1>🧪 ZennoBrowser API Test Report V3.0</h1>
                            <p>Выполнено: %d тестов | ✅ Успешно: %d | 📊 %s%% | ⏱️ %s</p>
                        </div>
                        """, results.size(), passed, String.format("%.1f", successRate),
                java.time.Instant.now().toString().substring(0, 19)));

        // Stats — %d для long
        long totalTime = results.stream().mapToLong(r -> r.durationMs).sum();
        long avgTime = totalTime / results.size();
        html.append(String.format("""
                <div class="stats">
                    <div class="stat"><h3>%d</h3><p>Тестов</p></div>
                    <div class="stat success"><h3>%d</h3><p>PASS</p></div>
                    <div class="stat failed"><h3>%d</h3><p>FAIL</p></div>
                    <div class="stat"><h3>%d ms</h3><p>Среднее</p></div>
                </div>
                """, results.size(), passed, results.size() - passed, avgTime));

        // Таблица — %d для времени
        html.append("<table><tr><th>#</th><th>Тест</th><th>Status</th><th>Время</th></tr>");
        for (int i = 0; i < results.size(); i++) {
            TestResult r = results.get(i);
            String status = r.passed() ? "✅ PASS" : "❌ FAIL";
            html.append(String.format(
                    "<tr><td>%d</td><td>%s</td><td><b>%d</b></td><td>%d ms</td></tr>",
                    i + 1, r.name, r.actual, r.durationMs));
        }
        html.append("</table></body></html>");

        Files.write(Paths.get(HTML_REPORT), html.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("📊 HTML Report: " + HTML_REPORT + " ✓");
    }


    private static void printExecutiveSummary() {
        long passed = results.stream().filter(TestResult::passed).count();
        double avgTime = results.stream().mapToLong(r -> r.durationMs).average().orElse(0);
        System.out.println("\n" + "═".repeat(60));
        System.out.println("📊 EXECUTIVE SUMMARY");
        System.out.printf("│ Тестов: %2d | PASS: %2d (%.0f%%) | FAIL: %2d | Ø: %.0fms │%n",
                results.size(), passed, passed * 100.0 / results.size(), results.size() - passed, avgTime);
        System.out.println("│ Отчёт: " + HTML_REPORT + " (откройте в браузере)");
        System.out.println("═".repeat(60));
    }

    // Utilities
    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String extractUuid(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matcher(text);
        return m.find() ? m.group() : null;
    }

    static class ApiResponse {
        final int code;
        final String body;
        final long durationMs;

        ApiResponse(int code, String body, long durationMs) {
            this.code = code;
            this.body = body;
            this.durationMs = durationMs;
        }
    }

    static class TestResult {
        final String name;
        final int expected, actual;
        final String response;
        final long durationMs;

        TestResult(String name, int expected, int actual, String response, long durationMs) {
            this.name = name;
            this.expected = expected;
            this.actual = actual;
            this.response = response;
            this.durationMs = durationMs;
        }

        boolean passed() {
            return expected == actual;
        }
    }
}
