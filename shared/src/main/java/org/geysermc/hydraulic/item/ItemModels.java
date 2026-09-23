package org.geysermc.hydraulic.item;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import team.unnamed.creative.item.CompositeItemModel;
import team.unnamed.creative.item.ConditionItemModel;
import team.unnamed.creative.item.ItemModel;
import team.unnamed.creative.item.RangeDispatchItemModel;
import team.unnamed.creative.item.ReferenceItemModel;
import team.unnamed.creative.item.SelectItemModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads the item model tree of the item definition format introduced in 1.21.4.
 * <p>
 * Before it, an item that changed appearance as it was used said so with
 * {@code overrides} and a {@code predicate} on its <i>model</i>. Now it says so
 * in {@code assets/<namespace>/items/<name>.json}, as a tree of conditions,
 * selections and range dispatches over the item's state.
 */
public final class ItemModels {
    private ItemModels() {
    }

    /**
     * Get the models a range dispatch steps through, in the order they are
     * shown: its fallback first, then its entries by ascending threshold.
     * <p>
     * Read in that order rather than by matching thresholds against known
     * values, because the thresholds differ between items -- a vanilla bow
     * steps at 0.65 and 0.9, a vanilla crossbow at 0.58 and 1.0 -- and a mod is
     * free to choose its own.
     *
     * @param model the item model to search
     * @return the model of each frame, or an empty list if there is no range
     *         dispatch in the tree
     */
    public static @NotNull List<Key> frames(@Nullable ItemModel model) {
        RangeDispatchItemModel rangeDispatch = findRangeDispatch(model);
        if (rangeDispatch == null) {
            return List.of();
        }

        List<Key> frames = new ArrayList<>();

        Key fallback = firstReference(rangeDispatch.fallback());
        if (fallback != null) {
            frames.add(fallback);
        }

        rangeDispatch.entries().stream()
                .sorted(Comparator.comparingDouble(RangeDispatchItemModel.Entry::threshold))
                .forEach(entry -> {
                    Key reference = firstReference(entry.model());
                    if (reference != null) {
                        frames.add(reference);
                    }
                });

        return frames;
    }

    /**
     * Get the model a selection shows for one of its cases, eg the crossbow
     * model holding an arrow.
     *
     * @param model the item model to search
     * @param when the case to look for
     * @return the model of that case, or {@code null} if it has none
     */
    public static @Nullable Key selected(@Nullable ItemModel model, @NotNull String when) {
        SelectItemModel select = find(model, SelectItemModel.class);
        if (select == null) {
            return null;
        }

        for (SelectItemModel.Case selectCase : select.cases()) {
            for (var value : selectCase.when()) {
                if (value.isJsonPrimitive() && when.equals(value.getAsString())) {
                    return firstReference(selectCase.model());
                }
            }
        }

        return null;
    }

    /**
     * Get the first plain model referenced in the tree, which is the one an
     * item that is not being used shows.
     *
     * @param model the item model to search
     * @return the referenced model, or {@code null} if the tree references none
     */
    public static @Nullable Key firstReference(@Nullable ItemModel model) {
        if (model instanceof ReferenceItemModel reference) {
            return reference.model();
        }

        // An item is drawn in its resting state until it is used, so prefer the
        // branch that state takes: a condition's on_false, and whatever a
        // selection or dispatch falls back to.
        for (ItemModel child : children(model)) {
            Key reference = firstReference(child);
            if (reference != null) {
                return reference;
            }
        }

        return null;
    }

    private static @Nullable RangeDispatchItemModel findRangeDispatch(@Nullable ItemModel model) {
        return find(model, RangeDispatchItemModel.class);
    }

    private static <T extends ItemModel> @Nullable T find(@Nullable ItemModel model, @NotNull Class<T> type) {
        if (type.isInstance(model)) {
            return type.cast(model);
        }

        for (ItemModel child : children(model)) {
            T found = find(child, type);
            if (found != null) {
                return found;
            }
        }

        return null;
    }

    private static @NotNull List<ItemModel> children(@Nullable ItemModel model) {
        List<ItemModel> children = new ArrayList<>();

        if (model instanceof ConditionItemModel condition) {
            children.add(condition.onFalse());
            children.add(condition.onTrue());
        } else if (model instanceof SelectItemModel select) {
            children.add(select.fallback());
            select.cases().forEach(selectCase -> children.add(selectCase.model()));
        } else if (model instanceof RangeDispatchItemModel rangeDispatch) {
            children.add(rangeDispatch.fallback());
            rangeDispatch.entries().forEach(entry -> children.add(entry.model()));
        } else if (model instanceof CompositeItemModel composite) {
            children.addAll(composite.models());
        }

        children.removeIf(java.util.Objects::isNull);
        return children;
    }
}
