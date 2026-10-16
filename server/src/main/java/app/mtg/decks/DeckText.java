package app.mtg.decks;

import app.mtg.decks.DeckStore.CardLine;
import app.mtg.decks.DeckStore.ParsedDeck;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Arena / Moxfield / Forge-ish text lists. */
public final class DeckText {
    private static final Pattern LINE = Pattern.compile("^(?:(\\d+)\\s+|[xX](\\d+)\\s+)?(.+)$");
    private static final Pattern SET_SUFFIX = Pattern.compile("\\s+\\(([A-Za-z0-9]{2,6})\\)\\s+[A-Za-z0-9-]+\\s*$");

    private DeckText() { }

    public static ParsedDeck parse(String text) {
        ParsedDeck deck = new ParsedDeck();
        if (text == null) return deck;
        String section = "main";
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }
            String header = line.toLowerCase(Locale.ROOT).replace(":", "").trim();
            if (header.equals("commander") || header.equals("commanders") || header.equals("[commander]")) {
                section = "commander";
                continue;
            }
            if (header.equals("sideboard") || header.equals("sideboard") || header.equals("[sideboard]")) {
                section = "sideboard";
                continue;
            }
            if (header.equals("deck") || header.equals("main") || header.equals("mainboard") || header.equals("[main]")) {
                section = "main";
                continue;
            }
            if (header.equals("companion") || header.equals("companions")) {
                section = "sideboard";
                deck.notes.add("Companion cards were added to the sideboard.");
                continue;
            }
            if (header.startsWith("about") || header.startsWith("name ")) {
                continue;
            }
            Matcher matcher = LINE.matcher(line);
            if (!matcher.matches()) continue;
            String qtyText = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            int qty = qtyText == null ? 1 : Integer.parseInt(qtyText);
            String name = matcher.group(3).trim();
            if (name.startsWith("* ")) name = name.substring(2).trim();
            String set = null;
            Matcher setMatcher = SET_SUFFIX.matcher(name);
            if (setMatcher.find()) {
                set = setMatcher.group(1);
                name = name.substring(0, setMatcher.start()).trim();
            }
            int bar = name.indexOf('|');
            if (bar > 0) {
                set = name.substring(bar + 1).trim();
                name = name.substring(0, bar).trim();
            }
            if (name.isEmpty() || name.equalsIgnoreCase("deck") ) continue;
            CardLine card = new CardLine();
            card.qty = Math.max(1, qty);
            card.name = name;
            card.set = set;
            switch (section) {
                case "commander" -> deck.commanders.add(card);
                case "sideboard" -> deck.sideboard.add(card);
                default -> deck.main.add(card);
            }
        }
        if (!deck.commanders.isEmpty() && (deck.name == null || deck.name.equals("Imported deck"))) {
            deck.name = deck.commanders.get(0).name;
        }
        return deck;
    }
}
