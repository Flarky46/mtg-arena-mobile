package app.mtg.lobby;

import app.mtg.decks.DeckStore;
import app.mtg.engine.WebGuiBase;
import app.mtg.engine.WebGuiGame;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.LobbyPlayer;
import forge.deck.Deck;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gui.interfaces.IGuiGame;
import forge.player.GamePlayerUtil;
import forge.player.LobbyPlayerHuman;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Rooms {
    private static final Logger LOG = Logger.getLogger("tabletop");
    private static final char[] CODE = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final String[] AI_NAMES = {
            "Asha", "Bram", "Cira", "Dorian", "Elspeth", "Fen", "Garruk", "Hana", "Ivo", "Jace", "Kira", "Linden"
    };

    private final WebGuiBase guiBase;
    private final DeckStore decks;
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();

    public Rooms(WebGuiBase guiBase, DeckStore decks) {
        this.guiBase = guiBase;
        this.decks = decks;
    }

    public Room create(String hostName, String format, String deckId) {
        Room room = new Room();
        room.code = newCode();
        room.format = "commander".equalsIgnoreCase(format) ? "commander" : "duel";
        room.hostToken = token();
        int seats = "commander".equals(room.format) ? 4 : 2;
        for (int i = 0; i < seats; i++) {
            Seat seat = new Seat();
            seat.index = i;
            seat.kind = i == 0 ? "human" : "open";
            seat.name = i == 0 ? cleanName(hostName, "You") : "";
            seat.token = i == 0 ? room.hostToken : null;
            seat.deckId = i == 0 ? deckId : null;
            room.seats.add(seat);
        }
        rooms.put(room.code, room);
        return room;
    }

    /** One human and the rest Forge AI, started immediately. */
    public Room quick(String name, String format, String deckId, boolean autopilot) {
        Room room = create(name, format, deckId);
        int ai = 1;
        for (int i = 1; i < room.seats.size(); i++) {
            Seat seat = room.seats.get(i);
            seat.kind = "ai";
            seat.name = AI_NAMES[(ai - 1) % AI_NAMES.length];
            seat.deckId = null;
            ai++;
        }
        room.autopilot = autopilot;
        start(room, room.hostToken);
        return room;
    }

    public Seat join(String code, String name) {
        Room room = require(code);
        synchronized (room) {
            if (!"lobby".equals(room.status)) {
                throw new IllegalStateException("That table has already started.");
            }
            for (Seat seat : room.seats) {
                if ("open".equals(seat.kind)) {
                    seat.kind = "human";
                    seat.name = cleanName(name, "Friend");
                    seat.token = token();
                    return seat;
                }
            }
        }
        throw new IllegalStateException("That table is full.");
    }

    public void updateSeat(String code, int index, String token, String kind, String deckId, String name) {
        Room room = require(code);
        synchronized (room) {
            if (!"lobby".equals(room.status)) {
                throw new IllegalStateException("The game has already started.");
            }
            Seat seat = room.seats.get(index);
            boolean host = room.hostToken.equals(token);
            boolean mine = token != null && token.equals(seat.token);
            if (!host && !mine) {
                throw new IllegalArgumentException("That seat isn't yours.");
            }
            if (kind != null) {
                if (!host && !"human".equals(kind)) {
                    throw new IllegalArgumentException("Only the host can change who sits there.");
                }
                if ("open".equals(kind) && index == 0) {
                    throw new IllegalArgumentException("The host seat stays occupied.");
                }
                if ("ai".equals(kind)) {
                    seat.kind = "ai";
                    seat.token = null;
                    if (seat.name == null || seat.name.isBlank() || "Friend".equals(seat.name)) {
                        seat.name = AI_NAMES[index % AI_NAMES.length];
                    }
                } else if ("open".equals(kind)) {
                    seat.kind = "open";
                    seat.token = null;
                    seat.name = "";
                    seat.deckId = null;
                }
            }
            if (deckId != null) {
                seat.deckId = deckId.isBlank() ? null : deckId;
            }
            if (name != null && !name.isBlank() && ("human".equals(seat.kind) || "ai".equals(seat.kind))) {
                seat.name = cleanName(name, seat.name);
            }
        }
    }

    public Room start(String code, String token) {
        Room room = require(code);
        start(room, token);
        return room;
    }

    private void start(Room room, String token) {
        synchronized (room) {
            if (!room.hostToken.equals(token)) {
                throw new IllegalArgumentException("Only the host can start the game.");
            }
            if (!"lobby".equals(room.status)) {
                return;
            }
            for (Seat seat : room.seats) {
                if ("open".equals(seat.kind)) {
                    throw new IllegalStateException("Seat " + (seat.index + 1) + " is still empty. Add a person or an AI.");
                }
            }
            boolean commander = "commander".equals(room.format);
            GameType type = commander ? GameType.Commander : GameType.Constructed;
            GameRules rules = new GameRules(type);
            rules.setAppliedVariants(EnumSet.of(type));
            rules.setGamesPerMatch(1);
            rules.setPlayForAnte(false);
            rules.setManaBurn(false);
            rules.setAISideboardingEnabled(false);

            List<RegisteredPlayer> players = new ArrayList<>();
            Map<RegisteredPlayer, IGuiGame> guis = new LinkedHashMap<>();
            int aiIndex = 0;
            for (Seat seat : room.seats) {
                String deckId = seat.deckId;
                if (deckId == null || deckId.isBlank()) {
                    deckId = decks.randomId(room.format);
                    seat.deckId = deckId;
                }
                Deck deck = new Deck(decks.loadForgeDeck(deckId, room.format));
                if (commander && deck.getCommanders().isEmpty()) {
                    throw new IllegalStateException(seat.name + " needs a commander. Import a commander deck or pick a precon.");
                }
                LobbyPlayer lobby;
                if ("human".equals(seat.kind)) {
                    lobby = new LobbyPlayerHuman(seat.name, 0, 0);
                } else {
                    lobby = GamePlayerUtil.createAiPlayer(seat.name, aiIndex, "");
                    aiIndex++;
                }
                RegisteredPlayer registered = commander ? RegisteredPlayer.forCommander(deck) : new RegisteredPlayer(deck);
                registered.setPlayer(lobby);
                players.add(registered);
                if ("human".equals(seat.kind)) {
                    WebGuiGame gui = new WebGuiGame(guiBase, room.autopilot);
                    seat.gui = gui;
                    guis.put(registered, gui);
                }
            }
            HostedMatch match = new HostedMatch();
            room.match = match;
            match.setEndGameHook(() -> room.status = "finished");
            match.setOnMatchOver(() -> room.status = "finished");
            room.status = "playing";
            try {
                match.startMatch(rules, EnumSet.of(type), players, guis, null);
            } catch (RuntimeException ex) {
                room.status = "lobby";
                room.error = ex.getMessage();
                LOG.log(Level.SEVERE, "failed to start " + room.code, ex);
                throw ex;
            }
        }
    }

    public Room require(String code) {
        if (code == null) throw new IllegalArgumentException("Missing room code.");
        Room room = rooms.get(code.toUpperCase());
        if (room == null) throw new IllegalArgumentException("No table with code " + code.toUpperCase() + ".");
        return room;
    }

    public Seat seatFor(Room room, String token) {
        if (token == null) return null;
        for (Seat seat : room.seats) {
            if (token.equals(seat.token)) return seat;
        }
        return null;
    }

    public JsonObject describe(Room room, String token) {
        JsonObject obj = new JsonObject();
        obj.addProperty("code", room.code);
        obj.addProperty("format", room.format);
        obj.addProperty("status", room.status);
        obj.addProperty("host", token != null && token.equals(room.hostToken));
        if (room.error != null) obj.addProperty("error", room.error);
        Seat mine = seatFor(room, token);
        if (mine != null) obj.addProperty("yourSeat", mine.index);
        JsonArray seats = new JsonArray();
        for (Seat seat : room.seats) {
            JsonObject row = new JsonObject();
            row.addProperty("index", seat.index);
            row.addProperty("kind", seat.kind);
            row.addProperty("name", seat.name == null ? "" : seat.name);
            row.addProperty("deckId", seat.deckId == null ? "" : seat.deckId);
            row.addProperty("yours", mine == seat);
            if (mine == seat && seat.token != null) {
                row.addProperty("token", seat.token);
            }
            seats.add(row);
        }
        obj.add("seats", seats);
        if (room.match != null && room.match.getGame() != null) {
            var game = room.match.getGame();
            obj.addProperty("gameOver", game.isGameOver());
            if (game.isGameOver() && game.getOutcome() != null) {
                obj.addProperty("draw", game.getOutcome().isDraw());
                if (!game.getOutcome().isDraw() && game.getOutcome().getWinningLobbyPlayer() != null) {
                    obj.addProperty("winner", game.getOutcome().getWinningLobbyPlayer().getName());
                }
            }
            try {
                obj.addProperty("turn", game.getPhaseHandler().getTurn());
                obj.addProperty("phase", String.valueOf(game.getPhaseHandler().getPhase()));
            } catch (RuntimeException ignored) {
                // Phase handler can be mid-update.
            }
        }
        return obj;
    }

    private String newCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(CODE[ThreadLocalRandom.current().nextInt(CODE.length)]);
            String code = sb.toString();
            if (!rooms.containsKey(code)) return code;
        }
        return Long.toString(System.currentTimeMillis(), 36).toUpperCase();
    }

    private static String token() {
        return Long.toHexString(ThreadLocalRandom.current().nextLong()) + Long.toHexString(ThreadLocalRandom.current().nextLong());
    }

    private static String cleanName(String name, String fallback) {
        if (name == null || name.isBlank()) return fallback;
        String trimmed = name.trim();
        return trimmed.length() > 24 ? trimmed.substring(0, 24) : trimmed;
    }

    public static final class Room {
        public String code;
        public String format;
        public String hostToken;
        public String status = "lobby";
        public String error;
        public boolean autopilot;
        public final List<Seat> seats = new ArrayList<>();
        public volatile HostedMatch match;
    }

    public static final class Seat {
        public int index;
        public String kind;
        public String name;
        public String token;
        public String deckId;
        public WebGuiGame gui;
    }
}
