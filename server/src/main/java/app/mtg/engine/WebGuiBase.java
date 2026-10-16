package app.mtg.engine;

import forge.gamemodes.match.HostedMatch;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;
import org.jupnp.UpnpServiceConfiguration;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Headless {@link IGuiBase}. Forge reads the asset directory from this object
 * the first time its constants class loads, so it must be installed before any
 * other Forge type is touched.
 */
public final class WebGuiBase implements IGuiBase {
    private static final Logger LOG = Logger.getLogger("tabletop");
    private final ExecutorService edt = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Forge-EDT");
        thread.setDaemon(true);
        return thread;
    });
    private final ISkinImage blank = new ISkinImage() { };
    private final IAudioClip silentClip = new IAudioClip() {
        @Override public void play(float value) { }
        @Override public boolean isDone() { return true; }
        @Override public void stop() { }
        @Override public void loop() { }
        @Override public void dispose() { }
    };
    private final IAudioMusic silentMusic = new IAudioMusic() {
        @Override public void play(Runnable onComplete) { if (onComplete != null) onComplete.run(); }
        @Override public void pause() { }
        @Override public void resume() { }
        @Override public void stop() { }
        @Override public void dispose() { }
        @Override public void setVolume(float value) { }
        @Override public boolean isPlaying() { return false; }
    };

    public boolean isEdt() {
        return isGuiThread();
    }

    public void runOnEdt(Runnable runnable) {
        if (isGuiThread()) {
            runnable.run();
        } else {
            edt.execute(runnable);
        }
    }

    @Override public boolean isRunningOnDesktop() { return true; }
    @Override public boolean isLibgdxPort() { return false; }
    @Override public String getCurrentVersion() { return "tabletop-1.0"; }

    @Override
    public String getAssetsDir() {
        String dir = System.getenv("FORGE_ASSETS");
        if (dir == null || dir.isBlank()) {
            dir = "/opt/forge-gui";
        }
        if (!dir.endsWith("/")) {
            dir += "/";
        }
        return dir;
    }

    @Override public void invokeInEdtNow(Runnable runnable) { runnable.run(); }

    @Override public void invokeInEdtLater(Runnable proc) { edt.execute(proc); }

    @Override
    public void invokeInEdtAndWait(Runnable proc) {
        if (isGuiThread()) {
            proc.run();
            return;
        }
        Future<?> future = edt.submit(proc);
        try {
            future.get();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void runBackgroundTask(String message, Runnable task) {
        Thread thread = new Thread(task, "Forge-bg");
        thread.setDaemon(true);
        thread.start();
    }

    @Override public boolean isGuiThread() { return "Forge-EDT".equals(Thread.currentThread().getName()); }
    @Override public ImageFetcher getImageFetcher() { return null; }
    @Override public ISkinImage getSkinIcon(FSkinProp skinProp) { return blank; }
    @Override public ISkinImage getUnskinnedIcon(String path) { return blank; }
    @Override public ISkinImage getCardArt(PaperCard card, boolean backFace) { return blank; }
    @Override public ISkinImage createLayeredImage(PaperCard card, FSkinProp background, String overlayFilename, float opacity) { return blank; }
    @Override public void clearImageCache() { }
    @Override public String encodeSymbols(String str, boolean formatReminderText) { return str == null ? "" : str; }
    @Override public int getAvatarCount() { return 0; }
    @Override public int getSleevesCount() { return 0; }
    @Override public float getScreenScale() { return 1f; }
    @Override public void preventSystemSleep(boolean preventSleep) { }
    @Override public void download(GuiDownloadService service, Consumer<Boolean> callback) { if (callback != null) callback.accept(false); }
    @Override public void copyToClipboard(String text) { }
    @Override public void browseToUrl(String url) { }
    @Override public void showCardList(String title, String message, List<PaperCard> list) { LOG.info(title + ": " + message); }
    @Override public boolean showBoxedProduct(String title, String message, List<PaperCard> list) { return true; }
    @Override public void showBugReportDialog(String title, String text, boolean showExitAppBtn) { LOG.warning(title + "\n" + text); }
    @Override public void showImageDialog(ISkinImage image, String message, String title) { LOG.info(title + ": " + message); }

    @Override
    public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) {
        LOG.info("dialog " + title + ": " + message + " -> default");
        return defaultOption;
    }

    @Override
    public String showInputDialog(String message, String title, FSkinProp icon, String initialInput, List<String> inputOptions, boolean isNumeric) {
        if (initialInput != null) return initialInput;
        if (inputOptions != null && !inputOptions.isEmpty()) return inputOptions.get(0);
        return isNumeric ? "0" : "Player";
    }

    @Override public String showFileDialog(String title, String defaultDir) { return null; }
    @Override public File getSaveFile(File defaultFile) { return defaultFile; }

    @Override
    public <T> List<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax, List<T> sourceChoices, List<T> destChoices) {
        return sourceChoices;
    }

    @Override
    public <T> List<T> getChoices(String message, int min, int max, Collection<T> choices, Collection<T> selected, FSerializableFunction<T, String> display) {
        return List.of();
    }

    @Override public PaperCard chooseCard(String title, String message, List<PaperCard> list) {
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    @Override public boolean isSupportedAudioFormat(File file) { return false; }
    @Override public IAudioClip createAudioClip(String filename) { return silentClip; }
    @Override public IAudioMusic createAudioMusic(String filename) { return silentMusic; }
    @Override public void startAltSoundSystem(String filename, boolean isSynchronized) { }
    @Override public void showSpellShop() { }
    @Override public void showBazaar() { }
    @Override public IGuiGame getNewGuiGame() { return new WebGuiGame(this, false); }
    @Override public HostedMatch hostMatch() { return new HostedMatch(); }
    @Override public UpnpServiceConfiguration getUpnpPlatformService() { return null; }
    @Override public boolean hasNetGame() { return false; }
}
