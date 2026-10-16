package app.mtg.engine;

import com.google.gson.JsonObject;
import forge.game.GameEntityView;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.item.PaperCard;
import forge.util.FSerializableFunction;

final class Labels {
    private Labels() { }

    static String text(Object value, FSerializableFunction<?, String> display) {
        if (value == null) {
            return "";
        }
        if (display != null) {
            try {
                @SuppressWarnings("unchecked")
                FSerializableFunction<Object, String> fn = (FSerializableFunction<Object, String>) display;
                String shown = fn.apply(value);
                if (shown != null && !shown.isBlank()) {
                    return shown;
                }
            } catch (RuntimeException ignored) {
                // Fall through to a type-specific label.
            }
        }
        if (value instanceof CardView card) {
            try {
                String name = card.getCurrentState().getName();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            } catch (RuntimeException ignored) {
                // Face-down or half-initialized views.
            }
            return "Card " + card.getId();
        }
        if (value instanceof PlayerView player) {
            String name = player.getLobbyPlayerName();
            return name == null || name.isBlank() ? "Player " + player.getId() : name;
        }
        if (value instanceof SpellAbilityView ability) {
            String description = ability.getDescription();
            return description == null || description.isBlank() ? "Ability" : description;
        }
        if (value instanceof PaperCard card) {
            return card.getName();
        }
        if (value instanceof GameEntityView entity) {
            return entity.toString();
        }
        return String.valueOf(value);
    }

    static JsonObject option(int index, Object value, FSerializableFunction<?, String> display) {
        JsonObject obj = new JsonObject();
        obj.addProperty("index", index);
        obj.addProperty("label", text(value, display));
        if (value instanceof CardView card) {
            obj.addProperty("cardId", card.getId());
            try {
                String name = card.getCurrentState().getOracleName();
                if (name == null || name.isBlank()) {
                    name = card.getCurrentState().getName();
                }
                if (name != null) {
                    obj.addProperty("name", name);
                }
            } catch (RuntimeException ignored) {
                obj.addProperty("name", "Card");
            }
        } else if (value instanceof PlayerView player) {
            obj.addProperty("playerId", player.getId());
        } else if (value instanceof PaperCard card) {
            obj.addProperty("name", card.getName());
        }
        return obj;
    }
}
