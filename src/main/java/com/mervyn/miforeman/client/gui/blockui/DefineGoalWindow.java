package com.mervyn.miforeman.client.gui.blockui;

import com.ldtteam.blockui.BOGuiGraphics;
import com.ldtteam.blockui.controls.ButtonImage;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.controls.TextFieldVanilla;
import com.ldtteam.blockui.views.BOWindow;
import com.mervyn.miforeman.client.gui.ClipboardChrome;
import com.mervyn.miforeman.client.gui.GoalFormResult;
import com.mervyn.miforeman.client.gui.GoalFormValidation;
import com.mervyn.miforeman.goal.ProductionGoal.TargetType;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.network.chat.Component;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * BlockUI trial for the "Define Goal" wizard step (see
 * .scratch/blockui-define-goal-trial/map.md). Built entirely in Java (no XML, per
 * issues/02-programmatic-pane-construction.md) and shown via {@link #openAsLayer()} on top of the
 * still-alive {@code ClipboardScreen} (per issues/01-boWindow-pane-embedding-architecture.md).
 * Hands its result back purely through the {@code onSubmit}/{@code onCancel} callbacks passed at
 * construction, mirroring {@code GraphCanvas}/{@code DetailCard}'s existing callback convention
 * rather than holding a reference to {@code ClipboardScreen} (per
 * issues/04-state-handoff-contract.md).
 *
 * <p>Draws its own copy of the clipboard's parchment background (same textures/constants as
 * {@code ClipboardScreen}) instead of relying on the backgrounded {@code ClipboardScreen} layer to
 * show through -- on at least one real client, that background layer rendered visibly darkened
 * while covered by this window (cause unconfirmed; several installed mods hook screen rendering).
 * Sized to the caller's current {@code guiWidth()}/{@code guiHeight()} and rendered at
 * {@link WindowRenderType#VANILLA} (1:1 with vanilla GUI scale, not BlockUI's default
 * {@code OVERSIZED_VANILLA}) so it lines up with -- and reads as -- the same panel, not a smaller
 * dialog floating on top of it.
 */
public class DefineGoalWindow extends BOWindow {
    private static final int PADDING = 8;
    private static final int FIELD_HEIGHT = 14;
    private static final int COLOR_TITLE = 0xFFDAA520;
    private static final int COLOR_LABEL = 0xFF8B7355;
    private static final int COLOR_ERROR = 0xFFCC3333;

    private final Consumer<GoalFormResult> onSubmit;
    private final Runnable onCancel;

    // Set true right before Next's onSubmit fires, so close()'s override doesn't also fire
    // onCancel for a successful submit -- see close() below.
    private boolean resultDelivered = false;

    private String goalName;
    private TargetType targetType;
    private String targetIdStr;
    private double rate;
    private boolean perHour;
    private double threshold;

    private ButtonImage typeButton;
    private ButtonImage unitButton;
    private ButtonImage nextButton;
    private Text errorText;

    public DefineGoalWindow(
            int panelWidth,
            int panelHeight,
            String goalName,
            TargetType targetType,
            String targetIdStr,
            double rate,
            boolean perHour,
            double threshold,
            Consumer<GoalFormResult> onSubmit,
            Runnable onCancel) {
        this(panelWidth, panelHeight, goalName, targetType, targetIdStr, rate, perHour, threshold, null, onSubmit, onCancel);
    }

    /**
     * @param initialError a server/traversal-side error to show immediately (see
     *                      ClipboardScreen#openDefineGoalWindow(String)), or null for none. Applied
     *                      after the initial {@link #revalidate()} so it isn't immediately clobbered
     *                      by client-side validation passing.
     */
    public DefineGoalWindow(
            int panelWidth,
            int panelHeight,
            String goalName,
            TargetType targetType,
            String targetIdStr,
            double rate,
            boolean perHour,
            double threshold,
            String initialError,
            Consumer<GoalFormResult> onSubmit,
            Runnable onCancel) {
        super(panelWidth, panelHeight);
        this.windowRenderType = WindowRenderType.VANILLA;
        this.goalName = goalName;
        this.targetType = targetType;
        this.targetIdStr = targetIdStr;
        this.rate = rate;
        this.perHour = perHour;
        this.threshold = threshold;
        this.onSubmit = onSubmit;
        this.onCancel = onCancel;

        buildUi();
        revalidate();
        if (initialError != null) {
            errorText.setText(Component.literal(initialError));
        }
    }

    @Override
    public void drawSelf(final BOGuiGraphics target, final double mx, final double my) {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        ClipboardChrome.drawBackground(target, 0, 0, getWidth(), getHeight());
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        super.drawSelf(target, mx, my);
    }

    private void buildUi() {
        int contentX = PADDING + ClipboardChrome.MAIN_BORDER + 2;
        int contentY = PADDING + ClipboardChrome.MAIN_BORDER + 4;

        Text title = new Text();
        title.setPosition(contentX, contentY);
        title.setSize(getWidth() - contentX - PADDING, 12);
        title.setText(Component.literal("Define Goal"));
        title.setColors(COLOR_TITLE);
        addChild(title);

        int y = contentY + 24;
        int contentW = getWidth() - contentX - (PADDING + ClipboardChrome.MAIN_BORDER);

        addChild(label("Goal Name", contentX, y, contentW));
        y += 10;
        TextFieldVanilla nameField = new TextFieldVanilla();
        nameField.setPosition(contentX, y);
        nameField.setSize(contentW, FIELD_HEIGHT);
        nameField.setMaxTextLength(64);
        nameField.setTextIgnoreLength(goalName == null ? "" : goalName);
        nameField.setHandler(tf -> {
            this.goalName = tf.getText();
            revalidate();
        });
        addChild(nameField);
        y += FIELD_HEIGHT + 8;

        addChild(label("Target Type & ID", contentX, y, contentW));
        y += 10;
        typeButton = vanillaButton(contentX, y, 55, FIELD_HEIGHT, targetType.name());
        typeButton.setHandler(btn -> {
            this.targetType = this.targetType == TargetType.ITEM ? TargetType.FLUID : TargetType.ITEM;
            typeButton.setText(Component.literal(this.targetType.name()));
            revalidate();
        });
        addChild(typeButton);

        TextFieldVanilla targetIdField = new TextFieldVanilla();
        targetIdField.setPosition(contentX + 60, y);
        targetIdField.setSize(contentW - 60, FIELD_HEIGHT);
        targetIdField.setMaxTextLength(256);
        targetIdField.setTextIgnoreLength(targetIdStr == null ? "" : targetIdStr);
        targetIdField.setHandler(tf -> {
            this.targetIdStr = tf.getText();
            revalidate();
        });
        addChild(targetIdField);
        y += FIELD_HEIGHT + 8;

        addChild(label(perHour ? "Rate (units/hour)" : "Rate (units/min)", contentX, y, contentW));
        y += 10;
        TextFieldVanilla rateField = new TextFieldVanilla();
        rateField.setPosition(contentX, y);
        rateField.setSize(contentW - 80, FIELD_HEIGHT);
        rateField.setMaxTextLength(32);
        rateField.setTextIgnoreLength(String.valueOf(rate));
        rateField.setHandler(tf -> {
            try {
                this.rate = Double.parseDouble(tf.getText());
            } catch (NumberFormatException e) {
                // handled by revalidate()'s error message, same as the vanilla EditBox responder
            }
            revalidate();
        });
        addChild(rateField);

        unitButton = vanillaButton(contentX + contentW - 70, y, 70, FIELD_HEIGHT, perHour ? "Per Hour" : "Per Min");
        unitButton.setHandler(btn -> {
            this.perHour = !this.perHour;
            unitButton.setText(Component.literal(this.perHour ? "Per Hour" : "Per Min"));
            revalidate();
        });
        addChild(unitButton);
        y += FIELD_HEIGHT + 8;

        addChild(label("Threshold (%)", contentX, y, contentW));
        y += 10;
        TextFieldVanilla thresholdField = new TextFieldVanilla();
        thresholdField.setPosition(contentX, y);
        thresholdField.setSize(contentW, FIELD_HEIGHT);
        thresholdField.setMaxTextLength(32);
        thresholdField.setTextIgnoreLength(String.valueOf((int) (threshold * 100)));
        thresholdField.setHandler(tf -> {
            try {
                double pct = Double.parseDouble(tf.getText());
                this.threshold = pct / 100.0;
            } catch (NumberFormatException e) {
                // handled by revalidate()'s error message, same as the vanilla EditBox responder
            }
            revalidate();
        });
        addChild(thresholdField);
        y += FIELD_HEIGHT + 6;

        errorText = new Text();
        errorText.setPosition(contentX, y);
        errorText.setSize(contentW, 14);
        errorText.setColors(COLOR_ERROR);
        addChild(errorText);

        int btnY = getHeight() - PADDING - ClipboardChrome.MAIN_BORDER - 22;
        ButtonImage cancelButton = vanillaButton(contentX, btnY, 80, 16, "Cancel");
        cancelButton.setHandler(btn -> close());
        addChild(cancelButton);

        nextButton = vanillaButton(contentX + contentW - 80, btnY, 80, 16, "Next ->");
        nextButton.setHandler(btn -> {
            if (nextButton.isEnabled()) {
                resultDelivered = true;
                GoalFormResult result = new GoalFormResult(goalName, targetType, targetIdStr, rate, perHour, threshold);
                close();
                onSubmit.accept(result);
            }
        });
        addChild(nextButton);
    }

    /**
     * BOWindow's default ESC handling ({@code onUnhandledKeyTyped}) just pops the layer via
     * {@code close()} directly, without ever running {@code onCancel}/{@code onSubmit} -- so ESC
     * used to leave the underlying ClipboardScreen stuck with no widgets for this step (only
     * openDefineGoalWindow() adds anything to it, and only when re-entering the step). Overriding
     * close() here means every dismissal path -- Cancel, ESC, or anything else -- is treated as a
     * cancel unless Next already committed via resultDelivered, matching this step's old vanilla
     * behavior where ESC and Cancel were already equivalent.
     */
    @Override
    public void close() {
        super.close();
        if (!resultDelivered) {
            resultDelivered = true;
            onCancel.run();
        }
    }

    private void revalidate() {
        Optional<String> error = GoalFormValidation.validateGoalInputs(goalName, targetIdStr, targetType, rate, threshold);
        if (error.isPresent()) {
            errorText.setText(Component.literal(error.get()));
            nextButton.setEnabled(false);
        } else {
            errorText.setText(Component.literal(""));
            nextButton.setEnabled(true);
        }
    }

    private Text label(String text, int x, int y, int w) {
        Text t = new Text();
        t.setPosition(x, y);
        t.setSize(w, 10);
        t.setText(Component.literal(text));
        t.setColors(COLOR_LABEL);
        t.setTextScale(0.85f);
        return t;
    }

    private ButtonImage vanillaButton(int x, int y, int w, int h, String text) {
        ButtonImage button = new ClipboardButtonImage();
        button.setColors(0xFFFFFFFF, 0xFFFFFFFF, 0xFFA0A0A0);
        button.setSize(w, h);
        button.setPosition(x, y);
        button.setText(Component.literal(text));
        return button;
    }
}
