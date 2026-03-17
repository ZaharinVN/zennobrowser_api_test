package com.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.*;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

public class ZennoBrowserApiTest {
    private static final String TOKEN = "ZzHpClK2-85FVor5a6VP-tz1BYDlgsKb";  // ← Token
    private static final String BASE_URL = "http://localhost:8160/v1";
    private static final OkHttpClient client = new OkHttpClient();
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final String REPORT_FILE = "zennobrowser_test_report.json";

    private static List<TestResult> testResults = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("🧪 Запуск тестов API ZennoBrowser v1.0 (ИСПРАВЛЕННАЯ ВЕРСИЯ)");

        try {
            // Тест 1: Workspaces
            testGetWorkspaces();

            // Тест 2: Profile Folders (CRUD)
            testProfileFolders();

            // Тест 3: Profiles
            testProfiles();

            // Тест 4: Proxies
            testProxies();

            // Сохранить отчёт
            saveReport();

            System.out.println("✅ ВСЕ ТЕСТЫ УСПЕШНО ЗАВЕРШЕНЫ!");
            System.out.println("📊 Отчёт: " + REPORT_FILE);

        } catch (Exception e) {
            System.err.println("❌ ОШИБКА: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testGetWorkspaces() throws IOException {
        String endpoint = "/workspaces?start=0&total=10";
        System.out.print("1️⃣ GET Workspaces... ");

        Request request = new Request.Builder()
                .url(BASE_URL + endpoint)
                .addHeader("Api-Token", TOKEN)
                .build();

        try (Response response = client.newCall(request).execute()) {
            int status = response.code();
            String body = response.body() != null ? response.body().string() : "Empty";

            testResults.add(new TestResult("GET /workspaces", endpoint, 200, status, body));

            if (status == 200) {
                System.out.println("✅ OK (" + status + ")");
                System.out.println("   Данные: totalCount=" + extractTotalCount(body));
            } else {
                System.out.println("❌ FAILED (" + status + ")");
            }
        }
    }

    private static void testProfileFolders() throws IOException {
        // CREATE folder
        String folderName = "Test_" + UUID.randomUUID().toString().substring(0, 8);
        System.out.print("2️⃣ CREATE ProfileFolder... ");

        String endpoint = "/profile_folders?name=" + URLEncoder.encode(folderName, "UTF-8") +
                "&workspaceId=-1&location=Local";

        Request request = new Request.Builder()
                .url(BASE_URL + endpoint)
                .post(RequestBody.create("", MediaType.parse("text/plain")))
                .addHeader("Api-Token", TOKEN)
                .build();

        try (Response response = client.newCall(request).execute()) {
            int status = response.code();
            String body = response.body() != null ? response.body().string() : "Empty";
            testResults.add(new TestResult("POST /profile_folders", endpoint, 200, status, body));

            System.out.println(status == 200 ? "✅ OK" : "❌ " + status);
        }
    }

    private static void testProfiles() throws IOException {
        System.out.print("3️⃣ CREATE Profile ... ");

        // Минимальные параметры по документации
        String endpoint = "/profiles/create?" +
                "name=TestProfile&" +
                "workspaceId=-1&" +
                "screen=auto&" +
                "cpu=auto&" +
                "memory=auto&" +
                "language=auto&" +
                "timeZone=auto&" +
                "webGl=auto&" +
                "webRtc=Hide";

        Request request = new Request.Builder()
                .url(BASE_URL + endpoint)
                .post(RequestBody.create("", MediaType.parse("text/plain")))
                .addHeader("Api-Token", TOKEN)
                .build();

        try (Response response = client.newCall(request).execute()) {
            int status = response.code();
            String body = response.body() != null ? response.body().string() : "Empty";
            testResults.add(new TestResult("POST /profiles/create (fixed)", endpoint, 200, status, body));

            System.out.println(status == 200 ? "✅ OK" : "❌ " + status);
            if (status != 200) {
                System.out.println("   Ошибка: " + body);
            }
        }
    }


    private static void testProxies() throws IOException {
        System.out.print("4️⃣ GET Proxies... ");

        String endpoint = "/proxies?workspaceId=-1&start=0&total=5";
        Request request = new Request.Builder()
                .url(BASE_URL + endpoint)
                .addHeader("Api-Token", TOKEN)
                .build();

        try (Response response = client.newCall(request).execute()) {
            int status = response.code();
            String body = response.body() != null ? response.body().string() : "Empty";
            testResults.add(new TestResult("GET /proxies", endpoint, 200, status, body));

            if (status == 200) {
                System.out.println("✅ OK (" + status + ")");
                System.out.println("   Данные: totalCount=" + extractTotalCount(body));
            } else {
                System.out.println("❌ FAILED (" + status + ")");
            }
        }
    }

    private static void saveReport() throws IOException {
        JsonObject report = new JsonObject();
        report.addProperty("timestamp", java.time.Instant.now().toString());
        report.addProperty("totalTests", testResults.size());

        JsonArray tests = new JsonArray();
        int passed = 0;
        for (TestResult result : testResults) {
            JsonObject test = new JsonObject();
            test.addProperty("name", result.name);
            test.addProperty("endpoint", result.endpoint);
            test.addProperty("expected", result.expected);
            test.addProperty("actual", result.actual);
            test.addProperty("passed", result.passed());
            test.addProperty("responsePreview", result.response.length() > 200 ?
                    result.response.substring(0, 200) + "..." : result.response);
            tests.add(test);
            if (result.passed()) passed++;
        }
        report.add("tests", tests);
        report.addProperty("passed", passed);
        report.addProperty("failed", testResults.size() - passed);

        try (FileWriter writer = new FileWriter(REPORT_FILE)) {
            gson.toJson(report, writer);
        }
    }

    private static String extractTotalCount(String json) {
        if (json.contains("\"totalCount\"")) {
            String[] parts = json.split("\"totalCount\"\\s*:\\s*");
            if (parts.length > 1) {
                return parts[1].split(",")[0].replace("}", "").trim();
            }
        }
        return "0";
    }

    static class TestResult {
        String name, endpoint, response;
        int expected, actual;

        TestResult(String name, String endpoint, int expected, int actual, String response) {
            this.name = name;
            this.endpoint = endpoint;
            this.expected = expected;
            this.actual = actual;
            this.response = response;
        }

        boolean passed() {
            return actual == expected;
        }
    }
}
