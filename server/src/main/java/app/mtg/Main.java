package app.mtg;

import app.mtg.decks.DeckStore;
import app.mtg.engine.ForgeBoot;
import app.mtg.engine.WebGuiBase;
import app.mtg.http.AppServer;
import app.mtg.images.ScryfallImages;
import app.mtg.lobby.Rooms;
import forge.gui.GuiBase;

import java.io.File;
import java.nio.file.Path;
import java.util.logging.Logger;

public final class Main {
    private static final Logger LOG = Logger.getLogger("tabletop");

    private Main() { }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        WebGuiBase gui = new WebGuiBase();
        // ForgeConstants reads the asset directory from this interface on first use.
        GuiBase.setInterface(gui);

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        File webRoot = new File(System.getenv().getOrDefault("WEB_ROOT", "web"));
        Path data = Path.of(System.getenv().getOrDefault("DATA_DIR", "data"));
        File assets = new File(gui.getAssetsDir());

        DeckStore decks = new DeckStore(data, assets);
        Rooms rooms = new Rooms(gui, decks);
        ScryfallImages images = new ScryfallImages();

        Thread loader = new Thread(() -> {
            try {
                ForgeBoot.init();
            } catch (Throwable ex) {
                LOG.severe("Engine failed: " + ex.getMessage());
            }
        }, "forge-boot");
        loader.setDaemon(true);
        loader.start();

        AppServer.start(port, webRoot, gui, decks, rooms, images);
        Thread.currentThread().join();
    }
}
