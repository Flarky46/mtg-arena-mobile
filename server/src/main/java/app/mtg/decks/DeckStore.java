package app.mtg.decks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.StaticData;
import forge.card.CardDb;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.deck.io.DeckSerializer;
import forge.item.PaperCard;
import org.apache.commons.lang3.tuple.Pair;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Imported decks live in a JSON file. Built-in decks are Forge's own precon
 * files, loaded when a seat actually needs one.
 */
public final class DeckStore {
    public static final String COMMANDER_DIR = "forge-gui/res/quest/commanderprecons";
    public static final String DUEL_DIR = "forge-gui/res/quest/precons";

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final File assets;
    private final Map<String, StoredDeck> imported = new LinkedHashMap<>();

    public DeckStore(Path dataDir, File assets) {
        this.assets = assets;
        this.file = dataDir.resolve("decks.json");
        load();
    }

    public synchronized List<JsonObject> list(String format) {
        List<JsonObject> rows = new ArrayList<>();
        for (StoredDeck deck : imported.values()) {
            if (format == null || format.isBlank() || format.equals(deck.format)) {
                rows.add(summary(deck));
            }
        }
        File folder = builtinFolder(format == null || format.isBlank() ? "duel" : format);
        if (folder.isDirectory()) {
            File[] files = folder.listFiles((dir, name) -> name.endsWith(".dck"));
            if (files != null) {
                java.util.Arrays.sort(files, Comparator.comparing(File::getName));
                for (File deckFile : files) {
                    JsonObject row = new JsonObject();
                    String id = builtinId(format, deckFile);
                    row.addProperty("id", id);
                    row.addProperty("name", readMetaName(deckFile));
                    row.addProperty("format", "commander".equals(format) ? "commander" : "duel");
                    row.addProperty("source", "builtin");
                    row.addProperty("commander", readCommander(deckFile));
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    public synchronized StoredDeck importText(String name, String format, String text) {
        ParsedDeck parsed = DeckText.parse(text);
        if (name != null && !name.isBlank()) {
            parsed.name = name.trim();
        }
        parsed.format = normalize(format, parsed);
        return saveParsed(parsed);
    }

    public synchronized StoredDeck importMoxfield(String url, String format) throws IOException {
        ParsedDeck parsed = MoxfieldClient.fetch(url);
        parsed.format = normalize(format, parsed);
        return saveParsed(parsed);
    }

    public synchronized StoredDeck getStored(String id) {
        return imported.get(id);
    }

    public Deck loadForgeDeck(String id, String format) {
        if (id != null && id.startsWith("builtin:")) {
            File file = builtinFile(id);
            if (file == null || !file.isFile()) {
                throw new IllegalArgumentException("Unknown built-in deck.");
            }
            Deck deck = DeckSerializer.fromFile(file);
            if (deck == null) {
                throw new IllegalArgumentException("Forge could not read " + file.getName());
            }
            return deck;
        }
        StoredDeck stored;
        synchronized (this) {
            stored = imported.get(id);
        }
        if (stored == null) {
            throw new IllegalArgumentException("Unknown deck.");
        }
        return toForge(stored);
    }

    public String randomId(String format) {
        List<JsonObject> rows = list(format);
        List<JsonObject> builtin = new ArrayList<>();
        for (JsonObject row : rows) {
            if ("builtin".equals(row.get("source").getAsString())) {
                builtin.add(row);
            }
        }
        if (builtin.isEmpty()) {
            throw new IllegalStateException("No built-in " + format + " decks are installed.");
        }
        return builtin.get(ThreadLocalRandom.current().nextInt(builtin.size())).get("id").getAsString();
    }

    private StoredDeck saveParsed(ParsedDeck parsed) {
        Deck deck = toForge(parsed);
        Pair<Deck, List<PaperCard>> valid = deck.getValid();
        List<String> unsupported = new ArrayList<>();
        for (PaperCard card : valid.getRight()) {
            unsupported.add(card.getName());
        }
        StoredDeck stored = fromForge(valid.getLeft(), parsed, unsupported);
        if (stored.main.isEmpty() && stored.commanders.isEmpty()) {
            throw new IllegalArgumentException("None of those cards are in the Forge card database.");
        }
        synchronized (this) {
            imported.put(stored.id, stored);
            persist();
        }
        return stored;
    }

    private Deck toForge(ParsedDeck parsed) {
        Deck deck = new Deck(parsed.name == null || parsed.name.isBlank() ? "Imported deck" : parsed.name);
        addAll(deck, DeckSection.Main, parsed.main);
        addAll(deck, DeckSection.Commander, parsed.commanders);
        addAll(deck, DeckSection.Sideboard, parsed.sideboard);
        return deck;
    }

    private Deck toForge(StoredDeck stored) {
        ParsedDeck parsed = new ParsedDeck();
        parsed.name = stored.name;
        parsed.main = stored.main;
        parsed.commanders = stored.commanders;
        parsed.sideboard = stored.sideboard;
        return toForge(parsed);
    }

    private void addAll(Deck deck, DeckSection section, List<CardLine> lines) {
        if (lines == null || lines.isEmpty()) return;
        CardPool pool = deck.getOrCreate(section);
        for (CardLine line : lines) {
            // CardPool.add(..., addAny) asks Forge to roll a random art index.
            // An unknown card has zero arts, and that roll throws. Resolve the
            // printing ourselves and add the PaperCard directly.
            pool.add(resolve(line), line.qty);
        }
    }

    private PaperCard resolve(CardLine line) {
        String set = line.set == null ? "" : line.set.trim().toUpperCase();
        StaticData data = StaticData.instance();
        data.attemptToLoadCard(line.name, set.isEmpty() ? null : set);
        CardDb db = data.getCommonCards();
        PaperCard card = set.isEmpty() ? null : db.getCard(line.name, set, 1);
        if (card == null) {
            card = db.getCard(line.name);
        }
        // Moxfield prints adventure and split cards as "Front // Back".
        if (card == null && line.name.contains("//")) {
            String front = line.name.split("//", 2)[0].trim();
            data.attemptToLoadCard(front, set.isEmpty() ? null : set);
            card = set.isEmpty() ? null : db.getCard(front, set, 1);
            if (card == null) {
                card = db.getCard(front);
            }
        }
        if (card == null) {
            card = db.createUnsupportedCard(line.name);
        }
        return card;
    }

    private StoredDeck fromForge(Deck deck, ParsedDeck parsed, List<String> unsupported) {
        StoredDeck stored = new StoredDeck();
        stored.id = "import-" + Long.toString(System.currentTimeMillis(), 36);
        stored.name = deck.getName();
        stored.format = parsed.format;
        stored.sourceUrl = parsed.sourceUrl;
        stored.unsupported = unsupported;
        stored.notes = parsed.notes;
        stored.main = linesFrom(deck.get(DeckSection.Main));
        stored.commanders = linesFrom(deck.get(DeckSection.Commander));
        stored.sideboard = linesFrom(deck.get(DeckSection.Sideboard));
        return stored;
    }

    private List<CardLine> linesFrom(CardPool pool) {
        List<CardLine> lines = new ArrayList<>();
        if (pool == null) return lines;
        for (Map.Entry<PaperCard, Integer> entry : pool) {
            CardLine line = new CardLine();
            line.qty = entry.getValue();
            line.name = entry.getKey().getName();
            line.set = entry.getKey().getEdition();
            lines.add(line);
        }
        return lines;
    }

    public JsonObject detail(StoredDeck deck) {
        JsonObject obj = summary(deck);
        obj.add("main", lines(deck.main));
        obj.add("commanders", lines(deck.commanders));
        obj.add("sideboard", lines(deck.sideboard));
        JsonArray unsupported = new JsonArray();
        if (deck.unsupported != null) {
            for (String name : deck.unsupported) unsupported.add(name);
        }
        obj.add("unsupported", unsupported);
        JsonArray notes = new JsonArray();
        if (deck.notes != null) {
            for (String note : deck.notes) notes.add(note);
        }
        obj.add("notes", notes);
        return obj;
    }

    private JsonObject summary(StoredDeck deck) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", deck.id);
        obj.addProperty("name", deck.name);
        obj.addProperty("format", deck.format);
        obj.addProperty("source", "import");
        obj.addProperty("sourceUrl", deck.sourceUrl == null ? "" : deck.sourceUrl);
        obj.addProperty("mainCount", count(deck.main));
        obj.addProperty("commander", deck.commanders == null || deck.commanders.isEmpty() ? "" : deck.commanders.get(0).name);
        obj.addProperty("unsupportedCount", deck.unsupported == null ? 0 : deck.unsupported.size());
        return obj;
    }

    private static int count(List<CardLine> lines) {
        int total = 0;
        if (lines != null) {
            for (CardLine line : lines) total += line.qty;
        }
        return total;
    }

    private JsonArray lines(List<CardLine> lines) {
        JsonArray arr = new JsonArray();
        if (lines != null) {
            for (CardLine line : lines) {
                JsonObject obj = new JsonObject();
                obj.addProperty("qty", line.qty);
                obj.addProperty("name", line.name);
                obj.addProperty("set", line.set == null ? "" : line.set);
                arr.add(obj);
            }
        }
        return arr;
    }

    private String normalize(String requested, ParsedDeck parsed) {
        if (parsed.commanders != null && !parsed.commanders.isEmpty()) return "commander";
        if (requested != null && !requested.isBlank()) {
            return "commander".equalsIgnoreCase(requested) ? "commander" : "duel";
        }
        return "duel";
    }

    private File builtinFolder(String format) {
        String rel = "commander".equals(format) ? "res/quest/commanderprecons" : "res/quest/precons";
        return new File(assets, rel);
    }

    private String builtinId(String format, File file) {
        String kind = "commander".equals(format) ? "commander" : "duel";
        return "builtin:" + kind + ":" + file.getName();
    }

    private File builtinFile(String id) {
        String[] parts = id.split(":", 3);
        if (parts.length < 3) return null;
        File folder = builtinFolder("commander".equals(parts[1]) ? "commander" : "duel");
        return new File(folder, parts[2]);
    }

    private String readMetaName(File file) {
        try {
            boolean meta = false;
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.equalsIgnoreCase("[metadata]")) {
                    meta = true;
                    continue;
                }
                if (trimmed.startsWith("[")) meta = false;
                if (meta && trimmed.toLowerCase().startsWith("name=")) {
                    return trimmed.substring(5).trim();
                }
            }
        } catch (IOException ignored) {
            // The filename is a fine label.
        }
        String name = file.getName();
        return name.endsWith(".dck") ? name.substring(0, name.length() - 4) : name;
    }

    private String readCommander(File file) {
        try {
            boolean section = false;
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.equalsIgnoreCase("[Commander]")) {
                    section = true;
                    continue;
                }
                if (trimmed.startsWith("[")) {
                    if (section) break;
                    continue;
                }
                if (section && !trimmed.isEmpty()) {
                    int bar = trimmed.indexOf('|');
                    String body = bar < 0 ? trimmed : trimmed.substring(0, bar);
                    int space = body.indexOf(' ');
                    return space < 0 ? body : body.substring(space + 1).trim();
                }
            }
        } catch (IOException ignored) {
            return "";
        }
        return "";
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            String json = Files.readString(file);
            StoredDeck[] decks = gson.fromJson(json, StoredDeck[].class);
            if (decks != null) {
                for (StoredDeck deck : decks) {
                    if (deck != null && deck.id != null) imported.put(deck.id, deck);
                }
            }
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, gson.toJson(imported.values()));
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public static final class StoredDeck {
        public String id;
        public String name;
        public String format;
        public String sourceUrl;
        public List<CardLine> commanders = new ArrayList<>();
        public List<CardLine> main = new ArrayList<>();
        public List<CardLine> sideboard = new ArrayList<>();
        public List<String> unsupported = new ArrayList<>();
        public List<String> notes = new ArrayList<>();
    }

    public static final class CardLine {
        public int qty = 1;
        public String name;
        public String set;
    }

    public static final class ParsedDeck {
        public String name = "Imported deck";
        public String format = "duel";
        public String sourceUrl;
        public List<CardLine> commanders = new ArrayList<>();
        public List<CardLine> main = new ArrayList<>();
        public List<CardLine> sideboard = new ArrayList<>();
        public List<String> notes = new ArrayList<>();
    }
}
