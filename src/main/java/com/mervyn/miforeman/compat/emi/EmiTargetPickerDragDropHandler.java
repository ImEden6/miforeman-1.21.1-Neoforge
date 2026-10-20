package com.mervyn.miforeman.compat.emi;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.mervyn.miforeman.client.gui.EmiTargetPickerScreen;

import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.widget.Bounds;

final class EmiTargetPickerDragDropHandler extends EmiDragDropHandler.BoundsBased<EmiTargetPickerScreen> {

    EmiTargetPickerDragDropHandler() {
        super((EmiTargetPickerScreen screen, BiConsumer<Bounds, Consumer<EmiIngredient>> register) -> {
            Bounds panel = EmiTargetPickerIntegration.getPanelBounds(screen);
            register.accept(panel, ingredient -> EmiTargetPickerIntegration.toTargetSelection(ingredient)
                    .ifPresent(selection -> screen.acceptDrop(selection.type(), selection.idStr())));
        });
    }
}
