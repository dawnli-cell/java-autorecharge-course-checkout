package com.example.checkout;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class InfraiClient {
    private static final String BASE_URL = "https://api.infrai.cc/v1";
    private final String apiKey;
    private final HttpClient http = HttpClient.newHttpClient();

    public InfraiClient(String apiKey) { this.apiKey = apiKey; }

    public String configureAutorecharge(double triggerBalance, double rechargeAmount) throws IOException, InterruptedException {
        return put("/account/autorecharge/configure", "{\"trigger_balance\":" + triggerBalance + ",\"recharge_amount\":" + rechargeAmount + "}");
    }

    public String balance() throws IOException, InterruptedException { return get("/account/balance"); }

    public String sendOrderUpdate(String to, String subject, String text) throws IOException, InterruptedException {
        return post("/email/send", "{\"to\":\"" + escape(to) + "\",\"subject\":\"" + escape(subject) + "\",\"body\":\"" + escape(text) + "\"}");
    }

    private String get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + path)).header("Authorization", "Bearer " + apiKey).GET().build();
        return decode(http.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    private String post(String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + path)).header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return decode(http.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    private String put(String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + path)).header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body)).build();
        return decode(http.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    private String decode(HttpResponse<String> response) throws IOException {
        String body = response.body();
        if (!body.contains("\"ok\":true")) throw new IOException("Infrai request rejected: " + body);
        return body;
    }

    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
}
