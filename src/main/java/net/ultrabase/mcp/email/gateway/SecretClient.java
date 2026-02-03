/*
 * up-mcp-email - MCP Server for Email
 * Copyright (c) 2024 César Obach / ultraBASE
 *
 * Licensed under the MIT License.
 */
package net.ultrabase.mcp.email.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Client for ultraPRO Desktop's Secret Management API.
 * Used to securely store and retrieve secrets like OAuth tokens.
 *
 * @author César Obach
 */
public class SecretClient {

    private static final Logger logger = LoggerFactory.getLogger(SecretClient.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String DEFAULT_GATEWAY_URL = "http://localhost:3000";

    private final String gatewayUrl;
    private final String providerId;
    private final String apiToken;

    /**
     * Creates a SecretClient.
     *
     * @param gatewayUrl Gateway base URL
     * @param providerId Provider identifier
     * @param apiToken   API token for authentication
     */
    public SecretClient(String gatewayUrl, String providerId, String apiToken) {
        this.gatewayUrl = gatewayUrl;
        this.providerId = providerId;
        this.apiToken = apiToken;
    }

    /**
     * Creates a SecretClient from environment variables.
     *
     * @param providerId Provider identifier
     * @return SecretClient or null if environment not configured
     */
    public static SecretClient fromEnvironment(String providerId) {
        String apiToken = System.getenv("ULTRAPRO_API_TOKEN");
        if (apiToken == null || apiToken.isEmpty()) {
            logger.debug("ULTRAPRO_API_TOKEN not set, Secret Management unavailable");
            return null;
        }

        String gatewayUrl = System.getenv("ULTRAPRO_GATEWAY_URL");
        if (gatewayUrl == null || gatewayUrl.isEmpty()) {
            gatewayUrl = DEFAULT_GATEWAY_URL;
        }

        return new SecretClient(gatewayUrl, providerId, apiToken);
    }

    /**
     * Retrieves a secret value.
     *
     * @param key Secret key
     * @return Secret value or null if not found
     */
    public String getSecret(String key) {
        try {
            String url = String.format("%s/secrets/%s/%s", gatewayUrl, providerId, key);
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + apiToken);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int responseCode = conn.getResponseCode();
            if (responseCode == 404) {
                return null;
            }

            if (responseCode != 200) {
                logger.warn("Failed to get secret {}: HTTP {}", key, responseCode);
                return null;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                JsonNode json = objectMapper.readTree(sb.toString());
                return json.has("value") ? json.get("value").asText() : null;
            }

        } catch (Exception e) {
            logger.warn("Failed to get secret {}: {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * Stores a secret value.
     *
     * @param key   Secret key
     * @param value Secret value
     * @return true if stored successfully
     */
    public boolean storeSecret(String key, String value) {
        try {
            String url = String.format("%s/secrets/%s/%s", gatewayUrl, providerId, key);
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("PUT");
            conn.setRequestProperty("Authorization", "Bearer " + apiToken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            String body = objectMapper.writeValueAsString(java.util.Map.of("value", value));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200 && responseCode != 201) {
                logger.warn("Failed to store secret {}: HTTP {}", key, responseCode);
                return false;
            }

            return true;

        } catch (Exception e) {
            logger.warn("Failed to store secret {}: {}", key, e.getMessage());
            return false;
        }
    }

    /**
     * Deletes a secret.
     *
     * @param key Secret key
     * @return true if deleted successfully
     */
    public boolean deleteSecret(String key) {
        try {
            String url = String.format("%s/secrets/%s/%s", gatewayUrl, providerId, key);
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("DELETE");
            conn.setRequestProperty("Authorization", "Bearer " + apiToken);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int responseCode = conn.getResponseCode();
            return responseCode == 200 || responseCode == 204;

        } catch (Exception e) {
            logger.warn("Failed to delete secret {}: {}", key, e.getMessage());
            return false;
        }
    }

    /**
     * Lists all secrets for this provider.
     *
     * @return Array of secret keys or empty array on error
     */
    public String[] listSecrets() {
        try {
            String url = String.format("%s/secrets/%s", gatewayUrl, providerId);
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + apiToken);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return new String[0];
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                JsonNode json = objectMapper.readTree(sb.toString());
                if (json.has("keys") && json.get("keys").isArray()) {
                    String[] keys = new String[json.get("keys").size()];
                    for (int i = 0; i < keys.length; i++) {
                        keys[i] = json.get("keys").get(i).asText();
                    }
                    return keys;
                }
            }

        } catch (Exception e) {
            logger.warn("Failed to list secrets: {}", e.getMessage());
        }
        return new String[0];
    }
}
