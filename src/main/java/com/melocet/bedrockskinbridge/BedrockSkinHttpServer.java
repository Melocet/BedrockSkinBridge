package com.melocet.bedrockskinbridge;

import com.sun.net.httpserver.HttpServer;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Tiny HTTP server that exposes the cached Bedrock skin URLs to the
 * SplitCraft website. Endpoints:
 * <ul>
 *   <li>{@code GET /bedrock-skin?name=Melocet~} — returns
 *       {@code {"name":..., "texture_url":...}} or 404</li>
 *   <li>{@code GET /health} — health check</li>
 * </ul>
 */
public final class BedrockSkinHttpServer {

    private final JavaPlugin plugin;
    private final BedrockSkinTracker tracker;
    private HttpServer server;

    public BedrockSkinHttpServer(JavaPlugin plugin, BedrockSkinTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("http.enabled", true)) {
            plugin.getLogger().info("[http] disabled in config");
            return;
        }
        int port = plugin.getConfig().getInt("http.port", 8082);
        String bind = plugin.getConfig().getString("http.bind", "0.0.0.0");
        try {
            server = HttpServer.create(new InetSocketAddress(bind, port), 0);
            server.createContext("/bedrock-skin", ex -> {
                if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                    ex.sendResponseHeaders(405, -1);
                    ex.close();
                    return;
                }
                String name = parseQueryParam(ex.getRequestURI().getQuery(), "name");
                String url = (name == null) ? null : tracker.getUrl(name);
                String body;
                int status;
                if (url == null) {
                    body = "{\"error\":\"not_found\"}";
                    status = 404;
                } else {
                    body = "{\"name\":\"" + jsonEscape(name)
                            + "\",\"texture_url\":\"" + jsonEscape(url) + "\"}";
                    status = 200;
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                ex.getResponseHeaders().add("Cache-Control", "public, max-age=300");
                ex.sendResponseHeaders(status, bytes.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
            });
            server.createContext("/health", ex -> {
                byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(body); }
            });
            server.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "BedrockSkinBridge-HTTP");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            plugin.getLogger().info("[http] serving /bedrock-skin on " + bind + ":" + port);
        } catch (IOException ex) {
            plugin.getLogger().warning("[http] could not bind " + bind + ":" + port + ": " + ex.getMessage());
            server = null;
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private static String parseQueryParam(String query, String key) {
        if (query == null) return null;
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            if (!key.equals(kv.substring(0, eq))) continue;
            try {
                return URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static String jsonEscape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
