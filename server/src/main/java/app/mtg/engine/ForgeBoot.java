package app.mtg.engine;

import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

import java.util.logging.Logger;

public final class ForgeBoot {
    private static final Logger LOG = Logger.getLogger("tabletop");
    private static volatile boolean ready;
    private static volatile Throwable failure;

    private ForgeBoot() { }

    public static boolean isReady() { return ready; }
    public static Throwable failure() { return failure; }

    public static synchronized void init() {
        if (ready || failure != null) {
            return;
        }
        long started = System.currentTimeMillis();
        LOG.info("Loading Forge card data from " + System.getenv().getOrDefault("FORGE_ASSETS", "/opt/forge-gui"));
        try {
            FModel.initialize(null, prefs -> {
                prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, true);
                prefs.setPref(FPref.PLAYER_NAME, "Player");
                prefs.setPref(FPref.ENFORCE_DECK_LEGALITY, false);
                prefs.setPref(FPref.UI_ANTE, false);
                prefs.setPref(FPref.UI_ANTE_MATCH_RARITY, false);
                prefs.setPref(FPref.UI_ENABLE_AI_CHEATS, false);
                prefs.setPref(FPref.UI_MANA_LOST_PROMPT, false);
                prefs.setPref(FPref.FILTERED_HANDS, false);
                prefs.setPref(FPref.DEV_MODE_ENABLED, false);
                prefs.setPref(FPref.UI_RANDOM_FOIL, false);
                prefs.setPref(FPref.UI_ENABLE_SOUNDS, false);
                prefs.setPref(FPref.UI_ENABLE_MUSIC, false);
                prefs.setPref(FPref.MATCH_AI_SIDEBOARDING_MODE, "Off");
                return null;
            });
            ready = true;
            LOG.info("Forge ready in " + (System.currentTimeMillis() - started) + " ms");
        } catch (Throwable ex) {
            failure = ex;
            LOG.log(java.util.logging.Level.SEVERE, "Forge failed to start", ex);
            throw new RuntimeException(ex);
        }
    }
}
