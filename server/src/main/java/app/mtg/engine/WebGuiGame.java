package app.mtg.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.LobbyPlayer;
import forge.deck.CardPool;
import forge.game.Game;
import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.event.GameEvent;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.gamemodes.match.YieldUpdate;
import forge.gui.control.PlaybackSpeed;
import forge.gui.interfaces.IGuiGame;
import forge.interfaces.IGameController;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.player.PlayerControllerHuman;
import forge.trackable.TrackableCollection;
import forge.trackable.TrackableObject;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * One human seat. Forge blocks this object whenever it needs a decision;
 * the phone answers over the websocket, on a different thread.
 */
public final class WebGuiGame implements IGuiGame {
    private static final Logger LOG = Logger.getLogger("tabletop");

    private final WebGuiBase base;
    private final boolean autopilot;
    private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final Map<Integer, CardView> cards = new LinkedHashMap<>();
    private final Map<Integer, PlayerView> players = new LinkedHashMap<>();
    private final AtomicBoolean broadcastQueued = new AtomicBoolean();

    private volatile GameView gameView;
    private volatile PlayerControllerHuman controller;
    private volatile String promptMessage = "";
    private volatile String okLabel = "OK";
    private volatile String cancelLabel = "Cancel";
    private volatile boolean okEnabled = true;
    private volatile boolean cancelEnabled = true;
    private volatile int selectMin;
    private volatile int selectMax;
    private final List<Integer> selectableCards = new ArrayList<>();
    private final List<Integer> selectablePlayers = new ArrayList<>();
    private final List<Integer> weakCards = new ArrayList<>();
    private final List<Integer> selected = new ArrayList<>();
    private volatile JsonObject decision;
    private volatile CompletableFuture<JsonObject> pending;
    private volatile boolean closed;
    private int autoTries;

    public WebGuiGame(WebGuiBase base, boolean autopilot) {
        this.base = base;
        this.autopilot = autopilot;
    }

    public boolean isAutopilot() { return autopilot; }

    public void addListener(Consumer<String> listener) {
        listeners.add(listener);
        listener.accept(payload());
    }

    public void removeListener(Consumer<String> listener) {
        listeners.remove(listener);
    }

    public PlayerControllerHuman controller() { return controller; }

    public void submit(JsonObject message) {
        String type = message.has("type") ? message.get("type").getAsString() : "";
        if ("answer".equals(type)) {
            CompletableFuture<JsonObject> wait = pending;
            if (wait != null && !wait.isDone()) {
                wait.complete(message);
            }
            return;
        }
        base.runOnEdt(() -> applyAction(message));
    }

    private void applyAction(JsonObject message) {
        if (controller == null) {
            return;
        }
        String action = message.has("action") ? message.get("action").getAsString() : "";
        try {
            switch (action) {
                case "ok" -> controller.selectButtonOk();
                case "cancel" -> controller.selectButtonCancel();
                case "concede" -> controller.concede();
                case "alpha" -> controller.alphaStrike();
                case "card" -> {
                    int id = message.get("cardId").getAsInt();
                    CardView card = cards.get(id);
                    if (card != null) {
                        if (selected.contains(id)) {
                            selected.remove((Integer) id);
                        } else {
                            selected.add(id);
                        }
                        controller.selectCard(card, null, null);
                    }
                }
                case "player" -> {
                    int id = message.get("playerId").getAsInt();
                    PlayerView player = players.get(id);
                    if (player != null) {
                        controller.selectPlayer(player, null);
                    }
                }
                case "stack" -> {
                    // Stack targeting is rare; the choice list covers it when Forge asks.
                }
                default -> LOG.fine("ignored action " + action);
            }
        } catch (RuntimeException ex) {
            LOG.warning("action failed: " + ex.getMessage());
        }
        publish();
    }

    private void publish() {
        if (closed) {
            return;
        }
        if (broadcastQueued.compareAndSet(false, true)) {
            // Always queue. Running inline would let autopilot recurse on the EDT
            // (click -> publish -> click) until the stack overflows.
            base.invokeInEdtLater(this::flush);
        }
    }

    private void flush() {
        broadcastQueued.set(false);
        push();
        if (autopilot && pending == null) {
            autoAct();
        }
    }

    private void push() {
        String body = payload();
        for (Consumer<String> listener : listeners) {
            try {
                listener.accept(body);
            } catch (RuntimeException ex) {
                listeners.remove(listener);
            }
        }
    }

    public String payload() {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", "state");
        Game game = currentGame();
        Player viewer = controller == null ? null : controller.getPlayer();
        JsonObject prompt = new JsonObject();
        prompt.addProperty("message", promptMessage == null ? "" : promptMessage);
        prompt.addProperty("ok", okLabel);
        prompt.addProperty("cancel", cancelLabel);
        prompt.addProperty("okEnabled", okEnabled);
        prompt.addProperty("cancelEnabled", cancelEnabled);
        prompt.addProperty("min", selectMin);
        prompt.addProperty("max", selectMax);
        prompt.add("selectable", ints(selectableCards));
        prompt.add("players", ints(selectablePlayers));
        prompt.add("weak", ints(weakCards));
        prompt.add("selected", ints(selected));
        envelope.add("state", Snapshots.project(game, viewer, prompt, decision));
        if (viewer != null) {
            envelope.addProperty("you", viewer.getId());
        }
        return envelope.toString();
    }

    private static JsonArray ints(List<Integer> values) {
        JsonArray arr = new JsonArray();
        for (Integer value : values) {
            arr.add(value);
        }
        return arr;
    }

    private Game currentGame() {
        if (controller != null && controller.getPlayer() != null) {
            return controller.getPlayer().getGame();
        }
        return gameView == null ? null : gameView.getGame();
    }

    @Override
    public void awaitInput(CountDownLatch done) throws InterruptedException {
        if (autopilot) {
            autoTries = 0;
            // Queued after Forge's own showMessage, so buttons and selectables exist.
            base.invokeInEdtLater(this::autoAct);
        }
        done.await();
    }

    private void autoAct() {
        if (!autopilot || controller == null || pending != null) {
            return;
        }
        try {
            for (int step = 0; step < 30; step++) {
                boolean mustChoose = selectMin > 0 || (!okEnabled && (!selectableCards.isEmpty() || !selectablePlayers.isEmpty()));
                if (mustChoose && selected.size() < Math.max(selectMin, 1)) {
                    Integer next = null;
                    for (Integer id : selectableCards) {
                        if (!selected.contains(id)) {
                            next = id;
                            break;
                        }
                    }
                    if (next != null && cards.containsKey(next)) {
                        boolean took = controller.selectCard(cards.get(next), null, null);
                        if (!took) {
                            retryAuto();
                            return;
                        }
                        selected.add(next);
                        continue;
                    }
                    if (!selectablePlayers.isEmpty()) {
                        PlayerView player = players.get(selectablePlayers.get(0));
                        if (player != null) {
                            controller.selectPlayer(player, null);
                            return;
                        }
                    }
                }
                if (okEnabled && selected.size() >= selectMin) {
                    controller.selectButtonOk();
                } else if (cancelEnabled && selectMin == 0) {
                    controller.selectButtonCancel();
                } else {
                    retryAuto();
                }
                return;
            }
        } catch (RuntimeException ex) {
            LOG.warning("autopilot: " + ex.getMessage());
        }
    }

    private void retryAuto() {
        if (autoTries++ > 12) {
            return;
        }
        Thread retry = new Thread(() -> {
            try {
                Thread.sleep(60);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            base.invokeInEdtLater(this::autoAct);
        }, "autopilot-retry");
        retry.setDaemon(true);
        retry.start();
    }

    private JsonObject ask(String kind, String title, String message, JsonArray options, int min, int max, JsonObject extra) {
        if (autopilot) {
            return null;
        }
        JsonObject body = extra == null ? new JsonObject() : extra;
        body.addProperty("kind", kind);
        body.addProperty("title", title == null ? "" : title);
        body.addProperty("message", message == null ? "" : message);
        body.addProperty("min", min);
        body.addProperty("max", max);
        if (options != null) {
            body.add("options", options);
        }
        String id = Long.toHexString(System.nanoTime());
        body.addProperty("id", id);
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending = future;
        decision = body;
        push();
        try {
            return future.get(8, TimeUnit.MINUTES);
        } catch (TimeoutException ex) {
            LOG.warning("decision timed out: " + title);
            return null;
        } catch (Exception ex) {
            LOG.warning("decision failed: " + ex.getMessage());
            return null;
        } finally {
            if (pending == future) {
                pending = null;
                decision = null;
            }
            publish();
        }
    }

    private List<Integer> chosenIndexes(JsonObject answer, int fallbackCount, int min, int max) {
        List<Integer> indexes = new ArrayList<>();
        if (answer != null && answer.has("indices") && answer.get("indices").isJsonArray()) {
            for (var el : answer.getAsJsonArray("indices")) {
                indexes.add(el.getAsInt());
            }
        }
        if (indexes.isEmpty()) {
            for (int i = 0; i < Math.min(min, fallbackCount); i++) {
                indexes.add(i);
            }
        }
        if (max >= 0 && indexes.size() > max) {
            return new ArrayList<>(indexes.subList(0, max));
        }
        return indexes;
    }

    private <T> List<T> pick(List<T> choices, int min, int max, FSerializableFunction<T, String> display, String title, String message) {
        if (choices == null || choices.isEmpty()) {
            return List.of();
        }
        int lo = Math.max(0, Math.min(min, choices.size()));
        int hi = Math.max(lo, Math.min(max, choices.size()));
        if (autopilot) {
            return new ArrayList<>(choices.subList(0, lo));
        }
        JsonArray options = new JsonArray();
        for (int i = 0; i < choices.size(); i++) {
            options.add(Labels.option(i, choices.get(i), display));
        }
        JsonObject answer = ask("choose", title, message, options, lo, hi, null);
        List<T> picked = new ArrayList<>();
        for (int index : chosenIndexes(answer, choices.size(), lo, hi)) {
            if (index >= 0 && index < choices.size() && !picked.contains(choices.get(index))) {
                picked.add(choices.get(index));
            }
        }
        while (picked.size() < lo) {
            picked.add(choices.get(picked.size()));
        }
        return picked;
    }

    private void remember(CardView card) {
        if (card != null) {
            cards.put(card.getId(), card);
        }
    }

    private void remember(Iterable<? extends CardView> views) {
        if (views == null) return;
        for (CardView card : views) {
            remember(card);
        }
    }

    @Override public void setGameView(GameView gameView) { this.gameView = gameView; publish(); }
    @Override public GameView getGameView() { return gameView; }

    @Override
    public void setOriginalGameController(PlayerView view, IGameController gameController) {
        if (view != null) {
            players.put(view.getId(), view);
        }
        if (gameController instanceof PlayerControllerHuman human) {
            controller = human;
        }
        publish();
    }

    @Override
    public void setGameController(PlayerView player, IGameController gameController) {
        if (player != null) {
            players.put(player.getId(), player);
        }
    }

    @Override public void setSpectator(IGameController spectator) { }
    @Override public void openView(TrackableCollection<PlayerView> myPlayers) { publish(); }
    @Override public void afterGameEnd() { closed = true; publish(); }
    @Override public void showCombat() { publish(); }

    @Override
    public void showPromptMessage(PlayerView playerView, String message, CardView card) {
        promptMessage = message == null ? "" : message;
        remember(card);
        publish();
    }

    @Override
    public void updateButtons(PlayerView owner, String label1, String label2, boolean enable1, boolean enable2, boolean focus1) {
        okLabel = label1 == null ? "OK" : label1;
        cancelLabel = label2 == null ? "Cancel" : label2;
        okEnabled = enable1;
        cancelEnabled = enable2;
        publish();
    }

    @Override public void flashIncorrectAction() { publish(); }
    @Override public void alertUser() { publish(); }
    @Override public void finishGame() { publish(); }
    @Override public void handleGameEvent(GameEvent event) { publish(); }
    @Override public void updateRevealedCards(TrackableCollection<CardView> collection) { remember(collection); publish(); }
    @Override public void setPanelSelection(CardView hostCard) { remember(hostCard); }

    @Override
    public SpellAbilityView getAbilityToPlay(CardView hostCard, List<SpellAbilityView> abilities, ITriggerEvent triggerEvent) {
        if (abilities == null || abilities.isEmpty()) {
            return null;
        }
        if (autopilot || abilities.size() == 1) {
            return abilities.get(0);
        }
        JsonArray options = new JsonArray();
        for (int i = 0; i < abilities.size(); i++) {
            options.add(Labels.option(i, abilities.get(i), null));
        }
        JsonObject answer = ask("ability", "Choose an action", hostCard == null ? "" : Labels.text(hostCard, null), options, 1, 1, null);
        int index = 0;
        if (answer != null && answer.has("indices")) {
            var arr = answer.getAsJsonArray("indices");
            if (arr.size() > 0) index = arr.get(0).getAsInt();
        }
        if (index < 0 || index >= abilities.size()) index = 0;
        return abilities.get(index);
    }

    @Override
    public Map<CardView, Integer> assignCombatDamage(CardView attacker, List<CardView> blockers, int damage, GameEntityView defender, boolean overrideOrder, boolean maySkip) {
        Map<CardView, Integer> result = new LinkedHashMap<>();
        List<CardView> options = new ArrayList<>();
        if (blockers != null) options.addAll(blockers);
        if (autopilot || options.isEmpty()) {
            if (!options.isEmpty()) {
                result.put(options.get(0), damage);
            } else {
                result.put(null, damage);
            }
            return result;
        }
        JsonArray json = new JsonArray();
        for (int i = 0; i < options.size(); i++) {
            json.add(Labels.option(i, options.get(i), null));
        }
        JsonObject defenderOption = new JsonObject();
        defenderOption.addProperty("index", options.size());
        defenderOption.addProperty("label", defender == null ? "Defending player" : "Damage the defender");
        json.add(defenderOption);
        JsonObject extra = new JsonObject();
        extra.addProperty("amount", damage);
        JsonObject answer = ask("assign", "Assign combat damage", "Distribute " + damage + " damage", json, 0, damage, extra);
        int remaining = damage;
        if (answer != null && answer.has("amounts")) {
            JsonObject amounts = answer.getAsJsonObject("amounts");
            for (String key : amounts.keySet()) {
                int index = Integer.parseInt(key);
                int qty = amounts.get(key).getAsInt();
                if (qty <= 0) continue;
                if (index >= 0 && index < options.size()) {
                    result.put(options.get(index), qty);
                } else {
                    result.put(null, qty);
                }
                remaining -= qty;
            }
        }
        if (result.isEmpty()) {
            result.put(options.get(0), damage);
        } else if (remaining > 0) {
            CardView first = result.keySet().iterator().next();
            result.put(first, result.get(first) + remaining);
        }
        return result;
    }

    @Override
    public Map<Object, Integer> assignGenericAmount(CardView effectSource, Map<Object, Integer> target, int amount, boolean atLeastOne, String amountLabel) {
        if (target == null || target.isEmpty()) {
            return target;
        }
        if (autopilot) {
            Object first = target.keySet().iterator().next();
            target.put(first, amount);
            return target;
        }
        List<Object> keys = new ArrayList<>(target.keySet());
        JsonArray options = new JsonArray();
        for (int i = 0; i < keys.size(); i++) {
            options.add(Labels.option(i, keys.get(i), null));
        }
        JsonObject extra = new JsonObject();
        extra.addProperty("amount", amount);
        JsonObject answer = ask("assign", amountLabel == null ? "Assign" : amountLabel, "Distribute " + amount, options, atLeastOne ? 1 : 0, amount, extra);
        if (answer != null && answer.has("amounts")) {
            for (Object key : keys) {
                target.put(key, 0);
            }
            JsonObject amounts = answer.getAsJsonObject("amounts");
            for (String key : amounts.keySet()) {
                int index = Integer.parseInt(key);
                if (index >= 0 && index < keys.size()) {
                    target.put(keys.get(index), amounts.get(key).getAsInt());
                }
            }
            return target;
        }
        Object first = keys.get(0);
        target.put(first, amount);
        return target;
    }

    @Override public void message(String message, String title) {
        LOG.info(title + ": " + message);
        publish();
    }

    @Override public void showErrorDialog(String message, String title) {
        LOG.warning(title + ": " + message);
        promptMessage = title + "\n" + message;
        publish();
    }

    @Override
    public boolean showConfirmDialog(String message, String title, String yesButtonText, String noButtonText, boolean defaultYes) {
        if (autopilot) return defaultYes;
        JsonArray options = new JsonArray();
        options.add(textOption(0, yesButtonText == null ? "Yes" : yesButtonText));
        options.add(textOption(1, noButtonText == null ? "No" : noButtonText));
        JsonObject answer = ask("choose", title, message, options, 1, 1, null);
        if (answer == null || !answer.has("indices") || answer.getAsJsonArray("indices").size() == 0) {
            return defaultYes;
        }
        return answer.getAsJsonArray("indices").get(0).getAsInt() == 0;
    }

    @Override
    public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) {
        if (options == null || options.isEmpty()) return defaultOption;
        if (autopilot) return defaultOption;
        JsonArray json = new JsonArray();
        for (int i = 0; i < options.size(); i++) json.add(textOption(i, options.get(i)));
        JsonObject answer = ask("choose", title, message, json, 1, 1, null);
        if (answer == null || !answer.has("indices") || answer.getAsJsonArray("indices").size() == 0) return defaultOption;
        int index = answer.getAsJsonArray("indices").get(0).getAsInt();
        return index >= 0 && index < options.size() ? index : defaultOption;
    }

    @Override
    public String showInputDialog(String message, String title, FSkinProp icon, String initialInput, List<String> inputOptions, boolean isNumeric) {
        if (autopilot) {
            if (initialInput != null) return initialInput;
            if (inputOptions != null && !inputOptions.isEmpty()) return inputOptions.get(0);
            return isNumeric ? "0" : "";
        }
        if (inputOptions != null && !inputOptions.isEmpty()) {
            return inputOptions.get(showOptionDialog(message, title, icon, inputOptions, 0));
        }
        JsonObject extra = new JsonObject();
        extra.addProperty("numeric", isNumeric);
        extra.addProperty("initial", initialInput == null ? "" : initialInput);
        JsonObject answer = ask("input", title, message, null, 0, 1, extra);
        if (answer != null && answer.has("text")) return answer.get("text").getAsString();
        return initialInput == null ? (isNumeric ? "0" : "") : initialInput;
    }

    @Override
    public boolean confirm(CardView card, String question, boolean defaultIsYes, List<String> options) {
        String yes = options != null && !options.isEmpty() ? options.get(0) : "Yes";
        String no = options != null && options.size() > 1 ? options.get(1) : "No";
        return showConfirmDialog(question, "Confirm", yes, no, defaultIsYes);
    }

    @Override
    public <T> List<T> getChoices(String message, int min, int max, List<T> choices, List<T> selected, FSerializableFunction<T, String> display) {
        return pick(choices, min, max, display, "Choose", message);
    }

    @Override
    public Integer getInteger(String message, int min, int max, boolean sortDesc) {
        return getInteger(message, min, max, 9);
    }

    @Override
    public Integer getInteger(String message, int min, int max, int cutoff) {
        if (autopilot) return min;
        JsonObject extra = new JsonObject();
        extra.addProperty("numeric", true);
        extra.addProperty("initial", String.valueOf(min));
        JsonObject answer = ask("input", "Choose a number", message + " (" + min + "–" + max + ")", null, min, max, extra);
        int value = min;
        if (answer != null && answer.has("text")) {
            try {
                value = Integer.parseInt(answer.get("text").getAsString().trim());
            } catch (NumberFormatException ignored) {
                value = min;
            }
        }
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public <T> T oneOrNone(String message, List<T> choices) {
        if (choices == null || choices.isEmpty()) return null;
        if (autopilot) return choices.get(0);
        List<T> picked = pick(choices, 0, 1, null, "Choose", message);
        return picked.isEmpty() ? null : picked.get(0);
    }

    @Override
    public <T> T one(String message, List<T> choices, FSerializableFunction<T, String> display) {
        if (choices == null || choices.isEmpty()) return null;
        if (autopilot) return choices.get(0);
        List<T> picked = pick(choices, 1, 1, display, "Choose", message);
        return picked.isEmpty() ? choices.get(0) : picked.get(0);
    }

    @Override
    public <T> void reveal(String message, List<T> items) {
        // Informational. Blocking the phone on a full deck list (Forge does this
        // once per AI seat before the opening hand) hides the table.
        if (message != null && !message.isBlank()) {
            LOG.info("reveal: " + message.replace('\n', ' '));
            if (promptMessage == null || promptMessage.isBlank()) {
                promptMessage = message;
            }
        }
        publish();
    }

    @Override
    public <T> List<T> many(String title, String topCaption, int min, int max, List<T> sourceChoices, List<T> destChoices, CardView card) {
        return pick(sourceChoices, min, max, null, title, topCaption);
    }

    @Override
    public <T> OrderResult<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax, List<T> sourceChoices, List<T> destChoices, CardView referenceCard, boolean sideboardingMode, boolean showRememberCheckbox) {
        List<T> source = sourceChoices == null ? List.of() : sourceChoices;
        if (autopilot || source.isEmpty()) {
            return new OrderResult<>(new ArrayList<>(source), false);
        }
        JsonArray options = new JsonArray();
        for (int i = 0; i < source.size(); i++) options.add(Labels.option(i, source.get(i), null));
        JsonObject extra = new JsonObject();
        extra.addProperty("ordering", true);
        JsonObject answer = ask("order", title, top == null ? "Put the first card on top." : top, options, source.size(), source.size(), extra);
        List<Integer> indexes = chosenIndexes(answer, source.size(), source.size(), source.size());
        List<T> ordered = new ArrayList<>();
        for (int index : indexes) {
            if (index >= 0 && index < source.size() && !ordered.contains(source.get(index))) {
                ordered.add(source.get(index));
            }
        }
        for (T item : source) {
            if (!ordered.contains(item)) ordered.add(item);
        }
        return new OrderResult<>(ordered, false);
    }

    @Override
    public <T> List<T> insertInList(String title, T newItem, List<T> oldItems) {
        List<T> items = new ArrayList<>();
        if (oldItems != null) items.addAll(oldItems);
        if (autopilot) {
            items.add(0, newItem);
            return items;
        }
        List<T> withNew = new ArrayList<>();
        withNew.add(newItem);
        withNew.addAll(items);
        OrderResult<T> ordered = order(title, "Order, first is on top", 0, 0, withNew, null, null, false, false);
        return ordered.ordered();
    }

    @Override
    public List<PaperCard> sideboard(CardPool sideboard, CardPool main, String message) {
        // Null keeps the deck that was loaded. Game 1 does not require sideboarding.
        return null;
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(String title, List<? extends GameEntityView> optionList, DelayedReveal delayedReveal, boolean isOptional) {
        if (optionList == null || optionList.isEmpty()) return null;
        if (autopilot) return isOptional ? null : optionList.get(0);
        List<GameEntityView> copy = new ArrayList<>(optionList);
        List<GameEntityView> picked = pick(copy, isOptional ? 0 : 1, 1, null, title, title);
        return picked.isEmpty() ? null : picked.get(0);
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(String title, List<? extends GameEntityView> optionList, int min, int max, DelayedReveal delayedReveal) {
        if (optionList == null) return List.of();
        List<GameEntityView> copy = new ArrayList<>(optionList);
        return new ArrayList<>(pick(copy, min, max, null, title, title));
    }

    @Override
    public List<CardView> manipulateCardList(String title, Iterable<CardView> cards, Iterable<CardView> manipulable, boolean toTop, boolean toBottom, boolean toAnywhere) {
        List<CardView> list = new ArrayList<>();
        if (cards != null) {
            for (CardView card : cards) list.add(card);
        }
        remember(list);
        if (autopilot) return list;
        OrderResult<CardView> ordered = order(title, "Reorder these cards", 0, 0, list, null, null, false, false);
        return ordered.ordered();
    }

    @Override public void setCard(CardView card) { remember(card); publish(); }
    @Override public void setPlayerAvatar(LobbyPlayer player, IHasIcon ihi) { }

    @Override
    public void setHighlighted(Iterable<? extends TrackableObject> objects, boolean on) { publish(); }

    @Override
    public void setSelectables(Iterable<CardView> cards, int min, int max) {
        selectableCards.clear();
        selected.clear();
        selectMin = min;
        selectMax = max;
        if (cards != null) {
            for (CardView card : cards) {
                remember(card);
                selectableCards.add(card.getId());
            }
        }
        // "Who goes first?" and similar prompts pass players through this input,
        // but only card views are registered above. With no cards, the seats themselves are the choices.
        if (selectableCards.isEmpty() && min > 0 && controller != null && controller.getPlayer() != null) {
            selectablePlayers.clear();
            for (Player player : controller.getPlayer().getGame().getPlayers()) {
                PlayerView view = player.getView();
                if (view == null) continue;
                players.put(view.getId(), view);
                selectablePlayers.add(view.getId());
            }
        }
        publish();
    }

    @Override
    public void setSelectableTargets(Iterable<? extends TrackableObject> targets) {
        selectablePlayers.clear();
        if (targets != null) {
            for (TrackableObject target : targets) {
                if (target instanceof PlayerView player) {
                    players.put(player.getId(), player);
                    selectablePlayers.add(player.getId());
                } else if (target instanceof CardView card) {
                    remember(card);
                    if (!selectableCards.contains(card.getId())) {
                        selectableCards.add(card.getId());
                    }
                }
            }
        }
        publish();
    }

    @Override public boolean canPickStackTargets() { return false; }

    @Override
    public void clearSelectables() {
        selectableCards.clear();
        selectablePlayers.clear();
        selected.clear();
        selectMin = 0;
        selectMax = 0;
        publish();
    }

    @Override public boolean isSelecting() { return !selectableCards.isEmpty() || !selectablePlayers.isEmpty(); }

    @Override
    public void setWeaklySelectable(Iterable<CardView> cards) {
        weakCards.clear();
        if (cards != null) {
            for (CardView card : cards) {
                remember(card);
                weakCards.add(card.getId());
            }
        }
        publish();
    }

    @Override public void clearWeaklySelectable() { weakCards.clear(); publish(); }
    @Override public boolean isGamePaused() { return false; }
    @Override public void setGamePause(boolean pause) { }
    @Override public PlaybackSpeed getGameSpeed() { return PlaybackSpeed.FAST; }
    @Override public void setGameSpeed(PlaybackSpeed gameSpeed) { }
    @Override public String getDayTime() { return ""; }
    @Override public void updateDayTime(String daytime) { publish(); }
    @Override public void awaitNextInput() { }
    @Override public void cancelAwaitNextInput() { }
    @Override public void updateAutoPassPrompt() { publish(); }
    @Override public void setCurrentPlayer(PlayerView player) {
        if (player != null) players.put(player.getId(), player);
        publish();
    }
    @Override public boolean isUiSetToSkipPhase(PlayerView playerTurn, PhaseType phase) {
        // Empty steps are passed by YIELD_AUTO_PASS_NO_ACTIONS, which still stops
        // when the player has a legal spell, ability, or attack. A blind skip here
        // would also skip an instant in upkeep or draw.
        return false;
    }
    @Override public void applyYieldUpdate(YieldUpdate update) { }
    @Override public boolean isNetGame() { return false; }
    @Override public void setNetGame() { }

    private static JsonObject textOption(int index, String label) {
        JsonObject obj = new JsonObject();
        obj.addProperty("index", index);
        obj.addProperty("label", label == null ? "" : label);
        return obj;
    }

    @SuppressWarnings("unused")
    private static <T> List<T> copy(Collection<T> values) {
        return values == null ? Collections.emptyList() : new ArrayList<>(values);
    }
}
