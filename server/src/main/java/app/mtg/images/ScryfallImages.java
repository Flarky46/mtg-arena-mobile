package app.mtg.images;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Card art is never stored in this project. We ask Scryfall for the image
 * URL it publishes and redirect the browser there.
 */
public final class ScryfallImages {
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final Object gate = new Object();
    private long lastCall;

    public String imageUrl(String name) {
        if (name == null || name.isBlank()) return null;
        String key = name.trim().toLowerCase();
        String cached = cache.get(key);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        synchronized (gate) {
            cached = cache.get(key);
            if (cached != null) return cached.isEmpty() ? null : cached;
            long wait = 80 - (System.currentTimeMillis() - lastCall);
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            lastCall = System.currentTimeMillis();
            String url = lookup(name.trim());
            cache.put(key, url == null ? "" : url);
            return url;
        }
    }

    private String lookup(String name) {
        try {
            String endpoint = "https://api.scryfall.com/cards/named?exact="
                    + URLEncoder.encode(name, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("User-Agent", "TabletopMTG/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            JsonObject card = JsonParser.parseString(response.body()).getAsJsonObject();
            if (card.has("image_uris") && card.get("image_uris").isJsonObject()) {
                JsonObject images = card.getAsJsonObject("image_uris");
                if (images.has("normal")) return images.get("normal").getAsString();
            }
            if (card.has("card_faces") && card.getAsJsonArray("card_faces").size() > 0) {
                JsonObject face = card.getAsJsonArray("card_faces").get(0).getAsJsonObject();
                if (face.has("image_uris")) {
                    return face.getAsJsonObject("image_uris").get("normal").getAsString();
                }
            }
        } catch (Exception ex) {
            return null;
        }
        return null;
    }
}
