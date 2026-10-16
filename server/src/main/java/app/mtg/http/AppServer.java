package app.mtg.http;

import app.mtg.decks.DeckStore;
import app.mtg.engine.ForgeBoot;
import app.mtg.engine.WebGuiBase;
import app.mtg.images.ScryfallImages;
import app.mtg.lobby.Rooms;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class AppServer {
    private static final Logger LOG = Logger.getLogger("tabletop");

    private AppServer() { }

    public static void start(int port, File webRoot, WebGuiBase gui, DeckStore decks, Rooms rooms, ScryfallImages images) throws Exception {
        Server server = new Server(port);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);
        context.addServlet(new ServletHolder(new ApiServlet(decks, rooms, images)), "/api/*");
        context.addServlet(new ServletHolder(new SocketServlet(rooms)), "/ws/*");
        context.addServlet(new ServletHolder(new StaticServlet(webRoot)), "/*");
        server.start();
        LOG.info("Listening on http://0.0.0.0:" + port);
    }

    static final class ApiServlet extends HttpServlet {
        private final DeckStore decks;
        private final Rooms rooms;
        private final ScryfallImages images;

        ApiServlet(DeckStore decks, Rooms rooms, ScryfallImages images) {
            this.decks = decks;
            this.rooms = rooms;
            this.images = images;
        }

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setHeader("Access-Control-Allow-Origin", "*");
            resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
            resp.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
                resp.setStatus(204);
                return;
            }
            String path = req.getPathInfo() == null ? "/" : req.getPathInfo();
            try {
                if ("/health".equals(path) && "GET".equals(req.getMethod())) {
                    health(resp);
                    return;
                }
                if ("/cards/image".equals(path) && "GET".equals(req.getMethod())) {
                    image(req, resp);
                    return;
                }
                requireReady();
                switch (path) {
                    case "/decks" -> { if (!"GET".equals(req.getMethod())) method(resp); else decks(req, resp); }
                    case "/decks/import" -> { if (!"POST".equals(req.getMethod())) method(resp); else importDeck(req, resp); }
                    case "/decks/detail" -> { if (!"GET".equals(req.getMethod())) method(resp); else deckDetail(req, resp); }
                    case "/rooms" -> { if (!"POST".equals(req.getMethod())) method(resp); else createRoom(req, resp); }
                    case "/rooms/join" -> { if (!"POST".equals(req.getMethod())) method(resp); else joinRoom(req, resp); }
                    case "/rooms/seat" -> { if (!"POST".equals(req.getMethod())) method(resp); else seat(req, resp); }
                    case "/rooms/start" -> { if (!"POST".equals(req.getMethod())) method(resp); else start(req, resp); }
                    case "/rooms/quick" -> { if (!"POST".equals(req.getMethod())) method(resp); else quick(req, resp); }
                    case "/rooms/status" -> { if (!"GET".equals(req.getMethod())) method(resp); else status(req, resp); }
                    default -> send(resp, 404, error("Nothing lives at that address."));
                }
            } catch (IllegalArgumentException | IllegalStateException ex) {
                LOG.log(java.util.logging.Level.WARNING, path, ex);
                send(resp, 400, error(ex.getMessage()));
            } catch (IOException ex) {
                send(resp, 502, error(ex.getMessage()));
            } catch (Exception ex) {
                LOG.log(java.util.logging.Level.SEVERE, path, ex);
                send(resp, 500, error(ex.getMessage() == null ? "Something went wrong." : ex.getMessage()));
            }
        }

        private void health(HttpServletResponse resp) throws IOException {
            JsonObject obj = new JsonObject();
            obj.addProperty("engine", "Forge");
            obj.addProperty("engineVersion", "2.0.16-SNAPSHOT");
            obj.addProperty("engineLicense", "GPL-3.0");
            obj.addProperty("ready", ForgeBoot.isReady());
            if (ForgeBoot.failure() != null) {
                obj.addProperty("error", ForgeBoot.failure().getMessage());
                send(resp, 500, obj);
            } else {
                send(resp, 200, obj);
            }
        }

        private void image(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            String name = req.getParameter("name");
            String url = images.imageUrl(name);
            if (url == null) {
                send(resp, 404, error("No Scryfall art for that card."));
                return;
            }
            resp.setStatus(302);
            resp.setHeader("Location", url);
            resp.setHeader("Cache-Control", "public, max-age=86400");
        }

        private void decks(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            String format = req.getParameter("format");
            JsonObject obj = new JsonObject();
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (JsonObject row : decks.list(format)) arr.add(row);
            obj.add("decks", arr);
            send(resp, 200, obj);
        }

        private void importDeck(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            JsonObject body = read(req);
            String format = text(body, "format");
            String name = text(body, "name");
            DeckStore.StoredDeck stored;
            if (body.has("url") && !body.get("url").getAsString().isBlank()) {
                stored = decks.importMoxfield(body.get("url").getAsString(), format);
            } else if (body.has("text")) {
                stored = decks.importText(name, format, body.get("text").getAsString());
            } else {
                throw new IllegalArgumentException("Paste a Moxfield link or a deck list.");
            }
            send(resp, 200, decks.detail(stored));
        }

        private void deckDetail(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            DeckStore.StoredDeck stored = decks.getStored(req.getParameter("id"));
            if (stored == null) throw new IllegalArgumentException("Unknown deck.");
            send(resp, 200, decks.detail(stored));
        }

        private void createRoom(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            JsonObject body = read(req);
            Rooms.Room room = rooms.create(text(body, "name"), text(body, "format"), emptyToNull(text(body, "deckId")));
            send(resp, 200, withToken(rooms.describe(room, room.hostToken), room.hostToken));
        }

        private void joinRoom(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            JsonObject body = read(req);
            Rooms.Seat seat = rooms.join(text(body, "code"), text(body, "name"));
            Rooms.Room room = rooms.require(text(body, "code"));
            send(resp, 200, withToken(rooms.describe(room, seat.token), seat.token));
        }

        private void seat(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            JsonObject body = read(req);
            int index = body.get("index").getAsInt();
            rooms.updateSeat(text(body, "code"), index, text(body, "token"),
                    body.has("kind") ? text(body, "kind") : null,
                    body.has("deckId") ? text(body, "deckId") : null,
                    body.has("name") ? text(body, "name") : null);
            Rooms.Room room = rooms.require(text(body, "code"));
            send(resp, 200, rooms.describe(room, text(body, "token")));
        }

        private void start(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            JsonObject body = read(req);
            Rooms.Room room = rooms.start(text(body, "code"), text(body, "token"));
            send(resp, 200, withToken(rooms.describe(room, text(body, "token")), text(body, "token")));
        }

        private void quick(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            JsonObject body = read(req);
            boolean autopilot = body.has("autopilot") && body.get("autopilot").getAsBoolean();
            Rooms.Room room = rooms.quick(text(body, "name"), text(body, "format"), emptyToNull(text(body, "deckId")), autopilot);
            send(resp, 200, withToken(rooms.describe(room, room.hostToken), room.hostToken));
        }

        private void status(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            Rooms.Room room = rooms.require(req.getParameter("code"));
            send(resp, 200, rooms.describe(room, req.getParameter("token")));
        }

        private void requireReady() {
            if (ForgeBoot.failure() != null) {
                throw new IllegalStateException("The rules engine failed to load.");
            }
            if (!ForgeBoot.isReady()) {
                throw new IllegalStateException("Still loading the card database. Try again in a moment.");
            }
        }

        private static JsonObject read(HttpServletRequest req) throws IOException {
            try (InputStream in = req.getInputStream()) {
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                if (body.isBlank()) return new JsonObject();
                return JsonParser.parseString(body).getAsJsonObject();
            }
        }

        private static String text(JsonObject obj, String key) {
            if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return "";
            return obj.get(key).getAsString();
        }

        private static String emptyToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }

        private static JsonObject withToken(JsonObject obj, String token) {
            obj.addProperty("token", token);
            return obj;
        }

        private static JsonObject error(String message) {
            JsonObject obj = new JsonObject();
            obj.addProperty("error", message == null ? "Request failed." : message);
            return obj;
        }

        private static void method(HttpServletResponse resp) throws IOException {
            send(resp, 405, error("That action isn't available."));
        }

        private static void send(HttpServletResponse resp, int status, JsonObject body) throws IOException {
            resp.setStatus(status);
            resp.setCharacterEncoding("UTF-8");
            resp.setContentType("application/json");
            resp.getWriter().write(body.toString());
        }
    }

    public static final class SocketServlet extends WebSocketServlet {
        private final Rooms rooms;

        SocketServlet(Rooms rooms) { this.rooms = rooms; }

        @Override
        public void configure(WebSocketServletFactory factory) {
            factory.getPolicy().setIdleTimeout(30 * 60 * 1000L);
            factory.getPolicy().setMaxTextMessageSize(2 * 1024 * 1024);
            factory.setCreator((req, resp) -> new SeatSocket(rooms));
        }
    }

    @WebSocket
    public static final class SeatSocket {
        private final Rooms rooms;
        private Session session;
        private Consumer<String> listener;
        private Rooms.Seat seat;

        public SeatSocket(Rooms rooms) { this.rooms = rooms; }

        @OnWebSocketConnect
        public void onConnect(Session session) {
            this.session = session;
            String query = session.getUpgradeRequest().getQueryString();
            String code = queryParam(query, "room");
            String token = queryParam(query, "token");
            try {
                Rooms.Room room = rooms.require(code);
                seat = rooms.seatFor(room, token);
                if (seat == null || seat.gui == null) {
                    session.close(1008, "That seat isn't in a game yet.");
                    return;
                }
                listener = text -> {
                    try {
                        if (session.isOpen()) {
                            session.getRemote().sendString(text);
                        }
                    } catch (IOException ex) {
                        session.close();
                    }
                };
                seat.gui.addListener(listener);
            } catch (RuntimeException ex) {
                session.close(1008, ex.getMessage());
            }
        }

        @OnWebSocketMessage
        public void onMessage(String message) {
            if (seat == null || seat.gui == null) return;
            try {
                JsonObject obj = JsonParser.parseString(message).getAsJsonObject();
                seat.gui.submit(obj);
            } catch (RuntimeException ex) {
                LOG.warning("socket message: " + ex.getMessage());
            }
        }

        @OnWebSocketClose
        public void onClose(int status, String reason) {
            if (seat != null && seat.gui != null && listener != null) {
                seat.gui.removeListener(listener);
            }
        }

        private static String queryParam(String query, String key) {
            if (query == null) return "";
            for (String part : query.split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0 && part.substring(0, eq).equals(key)) {
                    return java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
            return "";
        }
    }

    static final class StaticServlet extends HttpServlet {
        private final File root;

        StaticServlet(File root) { this.root = root; }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            String path = req.getRequestURI();
            if (path == null || path.isBlank() || "/".equals(path)) path = "/index.html";
            File file = new File(root, path);
            String rootPath = root.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            if (!filePath.startsWith(rootPath)) {
                resp.sendError(403);
                return;
            }
            if (!file.isFile()) {
                file = new File(root, "index.html");
            }
            String name = file.getName().toLowerCase();
            String type = "application/octet-stream";
            if (name.endsWith(".html")) type = "text/html; charset=utf-8";
            else if (name.endsWith(".css")) type = "text/css; charset=utf-8";
            else if (name.endsWith(".js")) type = "text/javascript; charset=utf-8";
            else if (name.endsWith(".json")) type = "application/json";
            else if (name.endsWith(".svg")) type = "image/svg+xml";
            else if (name.endsWith(".webmanifest")) type = "application/manifest+json";
            resp.setContentType(type);
            resp.setHeader("Cache-Control", name.equals("index.html") || name.equals("sw.js") ? "no-cache" : "public, max-age=3600");
            Files.copy(file.toPath(), resp.getOutputStream());
        }
    }
}
