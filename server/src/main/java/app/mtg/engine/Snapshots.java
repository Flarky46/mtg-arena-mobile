package app.mtg.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameLogEntry;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.combat.Combat;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the JSON a phone renders. Hidden zones stay hidden: a player sees
 * their own hand and any card Forge says they may look at.
 */
final class Snapshots {
    private Snapshots() { }

    static JsonObject project(Game game, Player viewer, JsonObject prompt, JsonObject decision) {
        JsonObject root = new JsonObject();
        if (game == null) {
            root.addProperty("phase", "Starting");
            root.add("players", new JsonArray());
            root.add("stack", new JsonArray());
            root.add("log", new JsonArray());
            if (prompt != null) root.add("prompt", prompt);
            if (decision != null) root.add("decision", decision);
            return root;
        }
        try {
            PhaseHandler phase = game.getPhaseHandler();
            root.addProperty("turn", phase == null ? 0 : phase.getTurn());
            root.addProperty("phase", phase == null || phase.getPhase() == null ? "" : phase.getPhase().name());
            root.addProperty("phaseLabel", phase == null || phase.getPhase() == null ? "" : phase.getPhase().nameForUi);
            Player turn = phase == null ? null : phase.getPlayerTurn();
            Player priority = phase == null ? null : phase.getPriorityPlayer();
            root.addProperty("turnPlayer", turn == null ? -1 : turn.getId());
            root.addProperty("priorityPlayer", priority == null ? -1 : priority.getId());
            root.addProperty("gameOver", game.isGameOver());
            root.addProperty("commander", game.getRules() != null && game.getRules().hasCommander());
            if (game.isGameOver() && game.getOutcome() != null) {
                root.addProperty("draw", game.getOutcome().isDraw());
                if (!game.getOutcome().isDraw() && game.getOutcome().getWinningLobbyPlayer() != null) {
                    root.addProperty("winner", game.getOutcome().getWinningLobbyPlayer().getName());
                }
            }
            if (viewer != null) {
                root.addProperty("you", viewer.getId());
            }
            JsonArray players = new JsonArray();
            for (Player player : game.getPlayers()) {
                players.add(playerJson(game, player, viewer));
            }
            // Lost players drop out of getPlayers(); still show registered ones who lost.
            if (players.size() == 0) {
                for (Player player : game.getRegisteredPlayers()) {
                    players.add(playerJson(game, player, viewer));
                }
            }
            root.add("players", players);
            root.add("stack", stackJson(game));
            root.add("combat", combatJson(game.getCombat()));
            root.add("log", logJson(game));
        } catch (RuntimeException ex) {
            root.addProperty("snapshotError", ex.getClass().getSimpleName());
        }
        if (prompt != null) {
            root.add("prompt", prompt);
        }
        if (decision != null) {
            root.add("decision", decision);
        }
        return root;
    }

    private static JsonObject playerJson(Game game, Player player, Player viewer) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", player.getId());
        obj.addProperty("name", player.getName());
        obj.addProperty("life", player.getLife());
        obj.addProperty("poison", player.getPoisonCounters());
        obj.addProperty("ai", player.getController() != null && player.getController().isAI());
        obj.addProperty("lost", player.hasLost());
        obj.addProperty("hand", zoneSize(player, ZoneType.Hand));
        obj.addProperty("library", zoneSize(player, ZoneType.Library));
        obj.addProperty("graveyard", zoneSize(player, ZoneType.Graveyard));
        obj.addProperty("exile", zoneSize(player, ZoneType.Exile));
        obj.add("mana", manaJson(player));
        obj.add("commanderDamage", commanderDamage(player));
        obj.add("battlefield", cardsJson(player, ZoneType.Battlefield, viewer, true));
        obj.add("graveyardCards", cardsJson(player, ZoneType.Graveyard, viewer, true));
        obj.add("exileCards", cardsJson(player, ZoneType.Exile, viewer, true));
        obj.add("command", cardsJson(player, ZoneType.Command, viewer, true));
        boolean seeHand = viewer != null && (viewer == player || viewer.equals(player));
        obj.add("handCards", cardsJson(player, ZoneType.Hand, viewer, seeHand));
        return obj;
    }

    private static int zoneSize(Player player, ZoneType zone) {
        try {
            return player.getZone(zone).size();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static JsonObject manaJson(Player player) {
        JsonObject mana = new JsonObject();
        try {
            mana.addProperty("W", player.getManaPool().getAmountOfColor(MagicColor.WHITE));
            mana.addProperty("U", player.getManaPool().getAmountOfColor(MagicColor.BLUE));
            mana.addProperty("B", player.getManaPool().getAmountOfColor(MagicColor.BLACK));
            mana.addProperty("R", player.getManaPool().getAmountOfColor(MagicColor.RED));
            mana.addProperty("G", player.getManaPool().getAmountOfColor(MagicColor.GREEN));
            mana.addProperty("C", player.getManaPool().getAmountOfColor((byte) ManaAtom.COLORLESS));
        } catch (RuntimeException ex) {
            mana.addProperty("C", 0);
        }
        return mana;
    }

    private static JsonArray commanderDamage(Player player) {
        JsonArray arr = new JsonArray();
        try {
            for (Map.Entry<Card, Integer> entry : player.getCommanderDamage()) {
                if (entry.getValue() == null || entry.getValue() <= 0 || entry.getKey() == null) {
                    continue;
                }
                JsonObject row = new JsonObject();
                row.addProperty("from", entry.getKey().getName());
                row.addProperty("cardId", entry.getKey().getId());
                row.addProperty("amount", entry.getValue());
                arr.add(row);
            }
        } catch (RuntimeException ignored) {
            // Commander damage is informational; a race just omits it this frame.
        }
        return arr;
    }

    private static JsonArray cardsJson(Player player, ZoneType zone, Player viewer, boolean reveal) {
        JsonArray arr = new JsonArray();
        if (!reveal) {
            return arr;
        }
        try {
            Combat combat = player.getGame().getCombat();
            for (Card card : player.getZone(zone).getCards()) {
                arr.add(cardJson(card, viewer, combat));
            }
        } catch (RuntimeException ignored) {
            // Zone changed while we were reading it.
        }
        return arr;
    }

    static JsonObject cardJson(Card card, Player viewer, Combat combat) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", card.getId());
        boolean faceDown = false;
        try {
            faceDown = card.isFaceDown();
        } catch (RuntimeException ignored) {
            faceDown = false;
        }
        boolean mayLook = viewer != null && safeLook(card, viewer);
        boolean hidden = faceDown && !mayLook && (viewer == null || card.getController() != viewer);
        // A player always knows their own face-down cards.
        if (viewer != null && (card.getController() == viewer || card.getOwner() == viewer)) {
            hidden = false;
        }
        obj.addProperty("faceDown", faceDown);
        obj.addProperty("hidden", hidden);
        String name = hidden ? "Face-down card" : safeName(card);
        obj.addProperty("name", name);
        obj.addProperty("imageName", hidden ? "" : safeOracle(card));
        obj.addProperty("tapped", safeTap(card));
        obj.addProperty("commander", safeCommander(card));
        obj.addProperty("ownerId", card.getOwner() == null ? -1 : card.getOwner().getId());
        obj.addProperty("controllerId", card.getController() == null ? -1 : card.getController().getId());
        if (!hidden) {
            try {
                CardView.CardStateView state = card.getView().getCurrentState();
                if (state.isCreature()) {
                    obj.addProperty("power", state.getPower());
                    obj.addProperty("toughness", state.getToughness());
                    obj.addProperty("creature", true);
                }
                if (state.isPlaneswalker() && state.getLoyalty() != null) {
                    obj.addProperty("loyalty", state.getLoyalty());
                }
                if (state.getType() != null) {
                    obj.addProperty("type", state.getType().toString());
                }
                if (state.getManaCost() != null) {
                    obj.addProperty("cost", state.getManaCost().toString());
                }
            } catch (RuntimeException ignored) {
                // Partial card views still render from the name.
            }
        }
        if (combat != null) {
            try {
                obj.addProperty("attacking", combat.isAttacking(card));
                if (combat.isAttacking(card)) {
                    GameEntity defender = combat.getDefenderByAttacker(card);
                    if (defender != null) {
                        obj.addProperty("attackingDefender", defender.getId());
                    }
                }
            } catch (RuntimeException ignored) {
                obj.addProperty("attacking", false);
            }
        }
        return obj;
    }

    private static boolean safeLook(Card card, Player viewer) {
        try {
            return card.mayPlayerLook(viewer);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static String safeName(Card card) {
        try {
            String name = card.getView().getCurrentState().getName();
            return name == null || name.isBlank() ? card.getName() : name;
        } catch (RuntimeException ex) {
            return card.getName();
        }
    }

    private static String safeOracle(Card card) {
        try {
            String name = card.getView().getCurrentState().getOracleName();
            if (name != null && !name.isBlank()) return name;
        } catch (RuntimeException ignored) {
            // Use the rules name below.
        }
        return card.getName();
    }

    private static boolean safeTap(Card card) {
        try {
            return card.isTapped();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean safeCommander(Card card) {
        try {
            return card.isCommander();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static JsonArray stackJson(Game game) {
        JsonArray arr = new JsonArray();
        try {
            List<SpellAbilityStackInstance> items = new ArrayList<>();
            for (SpellAbilityStackInstance item : game.getStack()) {
                items.add(item);
            }
            for (int i = items.size() - 1; i >= 0; i--) {
                SpellAbilityStackInstance item = items.get(i);
                JsonObject obj = new JsonObject();
                obj.addProperty("id", item.getId());
                obj.addProperty("text", item.getStackDescription());
                Card source = item.getSourceCard();
                if (source != null) {
                    obj.addProperty("cardId", source.getId());
                    obj.addProperty("name", source.getName());
                    obj.addProperty("imageName", source.getName());
                }
                if (item.getActivatingPlayer() != null) {
                    obj.addProperty("playerId", item.getActivatingPlayer().getId());
                    obj.addProperty("player", item.getActivatingPlayer().getName());
                }
                arr.add(obj);
            }
        } catch (RuntimeException ignored) {
            // Stack mutated mid-read.
        }
        return arr;
    }

    private static JsonObject combatJson(Combat combat) {
        JsonObject obj = new JsonObject();
        JsonArray attackers = new JsonArray();
        obj.add("attackers", attackers);
        if (combat == null) {
            return obj;
        }
        try {
            if (combat.getAttackingPlayer() != null) {
                obj.addProperty("attackingPlayer", combat.getAttackingPlayer().getId());
            }
            for (Card attacker : combat.getAttackers()) {
                JsonObject row = new JsonObject();
                row.addProperty("cardId", attacker.getId());
                row.addProperty("name", attacker.getName());
                GameEntity defender = combat.getDefenderByAttacker(attacker);
                if (defender != null) {
                    row.addProperty("defenderId", defender.getId());
                    row.addProperty("defender", defender.toString());
                }
                JsonArray blockers = new JsonArray();
                for (Card blocker : combat.getBlockers(attacker)) {
                    JsonObject b = new JsonObject();
                    b.addProperty("cardId", blocker.getId());
                    b.addProperty("name", blocker.getName());
                    blockers.add(b);
                }
                row.add("blockers", blockers);
                attackers.add(row);
            }
        } catch (RuntimeException ignored) {
            // Combat ended between reads.
        }
        return obj;
    }

    private static JsonArray logJson(Game game) {
        JsonArray arr = new JsonArray();
        try {
            List<GameLogEntry> entries = game.getGameLog().getLogEntries(null);
            int start = Math.max(0, entries.size() - 14);
            List<String> lines = new ArrayList<>();
            for (int i = start; i < entries.size(); i++) {
                lines.add(String.valueOf(entries.get(i)));
            }
            // getLogEntries is newest-first.
            for (int i = lines.size() - 1; i >= 0; i--) {
                arr.add(lines.get(i));
            }
        } catch (RuntimeException ignored) {
            // Log is optional.
        }
        return arr;
    }
}
