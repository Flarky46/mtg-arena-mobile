package app.mtg.decks;

import app.mtg.decks.DeckStore.CardLine;
import app.mtg.decks.DeckStore.ParsedDeck;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a public Moxfield deck through {@code /v2/decks/all/{publicId}}.
 * Moxfield sits behind a CDN that sometimes refuses data-center addresses;
 * the caller turns that into a clear "paste the list" message.
 */
public final class MoxfieldClient {
    private static final Pattern ID = Pattern.compile("(?:moxfield\\.com/decks/|^)([A-Za-z0-9_-]{6,})/?$");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private MoxfieldClient() { }

    public static ParsedDeck fetch(String url) throws IOException {
        String id = publicId(url);
        if (id == null) {
            throw new IOException("That doesn't look like a Moxfield deck link.");
        }
        String endpoint = "https://api.moxfield.com/v2/decks/all/" + id;
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(25))
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0 (compatible; TabletopMTG/1.0; +https://github.com/)")
                .header("Referer", "https://www.moxfield.com/")
                .header("Origin", "https://www.moxfield.com")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Moxfield request was interrupted.");
        }
        if (response.statusCode() == 403 || response.statusCode() == 503) {
            throw new IOException("Moxfield's CDN refused this server (" + response.statusCode()
                    + "). Open the deck, choose Export, and paste the deck list instead.");
        }
        if (response.statusCode() == 404) {
            throw new IOException("Moxfield has no public deck with that id. Private decks can't be imported by URL.");
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Moxfield returned HTTP " + response.statusCode() + ".");
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        ParsedDeck deck = new ParsedDeck();
        deck.name = text(root, "name", "Moxfield deck");
        deck.sourceUrl = text(root, "publicUrl", url);
        String format = text(root, "format", "");
        deck.format = format.toLowerCase().contains("commander") || format.toLowerCase().contains("edh")
                ? "commander" : "duel";
        readBoard(root, "mainboard", deck.main);
        readBoard(root, "commanders", deck.commanders);
        readBoard(root, "sideboard", deck.sideboard);
        readBoard(root, "companions", deck.sideboard);
        readBoard(root, "signatureSpells", deck.commanders);
        if (root.has("boards") && root.get("boards").isJsonObject()) {
            JsonObject boards = root.getAsJsonObject("boards");
            readNested(boards, "mainboard", deck.main);
            readNested(boards, "commanders", deck.commanders);
            readNested(boards, "sideboard", deck.sideboard);
        }
        if (deck.main.isEmpty() && deck.commanders.isEmpty()) {
            throw new IOException("Moxfield returned a deck with no cards.");
        }
        return deck;
    }

    private static void readBoard(JsonObject root, String name, java.util.List<CardLine> into) {
        if (!root.has(name) || !root.get(name).isJsonObject()) return;
        JsonObject board = root.getAsJsonObject(name);
        if (board.has("cards")) {
            readCardMap(board.get("cards"), into);
        } else {
            readCardMap(board, into);
        }
    }

    private static void readNested(JsonObject boards, String name, java.util.List<CardLine> into) {
        if (!boards.has(name) || !boards.get(name).isJsonObject()) return;
        JsonObject board = boards.getAsJsonObject(name);
        if (board.has("cards")) {
            readCardMap(board.get("cards"), into);
        }
    }

    private static void readCardMap(JsonElement element, java.util.List<CardLine> into) {
        if (element == null || !element.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject row = entry.getValue().getAsJsonObject();
            if (!row.has("quantity") && !row.has("card")) continue;
            JsonObject card = row.has("card") && row.get("card").isJsonObject()
                    ? row.getAsJsonObject("card") : row;
            String cardName = text(card, "name", entry.getKey());
            if (cardName.isBlank()) continue;
            CardLine line = new CardLine();
            line.qty = row.has("quantity") ? Math.max(1, row.get("quantity").getAsInt()) : 1;
            line.name = cardName;
            line.set = card.has("set") ? card.get("set").getAsString() : null;
            into.add(line);
        }
    }

    private static String text(JsonObject obj, String key, String fallback) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return fallback;
    }

    static String publicId(String url) {
        if (url == null) return null;
        String trimmed = url.trim();
        Matcher matcher = ID.matcher(trimmed);
        if (matcher.find()) return matcher.group(1);
        int slash = trimmed.lastIndexOf('/');
        if (slash >= 0 && slash < trimmed.length() - 1) {
            String tail = trimmed.substring(slash + 1);
            if (tail.matches("[A-Za-z0-9_-]{6,}")) return tail;
        }
        return null;
    }
}
