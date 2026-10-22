package com.mervyn.miforeman.client.gui;

import com.mervyn.miforeman.client.gui.ColourPalette.ColourKey;
import com.mervyn.miforeman.client.gui.widget.ChannelSlider;
import com.mervyn.miforeman.client.gui.widget.ClipboardButton;
import com.mervyn.miforeman.goal.MachineStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;

public class ColourPickerScreen extends Screen {
    private static final int MIN_GUI_WIDTH = 440;
    private static final int MIN_GUI_HEIGHT = 230;
    private static final int PADDING = 8;
    private static final int COLOUR_TITLE = 0xFFDAA520;
    private static final int COLOUR_LABEL = 0xFF8B7355;
    private static final int COLOUR_TEXT = 0xFF3A2A18;
    private static final int COLOUR_ERROR = MachineStatus.RED.colour();
    private static final int ROW_HEIGHT = 16;
    private static final int SWATCH_SIZE = 12;
    private static final int PREVIEW_HEIGHT = SWATCH_SIZE * 2;
    private static final int LABEL_TO_SWATCH = 14;
    private static final int SECTION_GAP = 8;
    private static final int SLIDER_GAP = 3;
    private static final int SLIDER_WIDTH = 150;
    private static final Pattern HEX_PATTERN = Pattern.compile("#?[0-9A-Fa-f]{8}");

    private enum ColourMode {
        RGB(new ChannelSlider.Channel("R", 0, 255), new ChannelSlider.Channel("G", 0, 255), new ChannelSlider.Channel("B", 0, 255)),
        HSV(new ChannelSlider.Channel("H", 0, 360), new ChannelSlider.Channel("S", 0, 100), new ChannelSlider.Channel("V", 0, 100)),
        HSL(new ChannelSlider.Channel("H", 0, 360), new ChannelSlider.Channel("S", 0, 100), new ChannelSlider.Channel("L", 0, 100));

        final ChannelSlider.Channel a, b, c;

        ColourMode(ChannelSlider.Channel a, ChannelSlider.Channel b, ChannelSlider.Channel c) {
            this.a = a;
            this.b = b;
            this.c = c;
        }

        ColourMode next() {
            ColourMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    private final Screen backTarget;
    private ColourKey selectedKey = ColourKey.values()[0];
    private ColourMode mode = ColourMode.RGB;
    /** Kept so hue survives when saturation or lightness is zero. */
    private float cachedHue;
    private int channelA, channelB, channelC;
    private ChannelSlider sliderA, sliderB, sliderC;
    private EditBox hexBox;
    private boolean hexInvalid;

    private final Map<ColourKey, Integer> rowY = new EnumMap<>(ColourKey.class);
    private int listX, listY;
    private int editorLabelY, editorSwatchY, editorModeY, editorSlider1Y, editorSlider2Y, editorSlider3Y, editorHexY, editorResetY, editorHintY;

    public ColourPickerScreen(Screen backTarget) {
        super(Component.translatable("miforeman.screen.colours.title"));
        this.backTarget = backTarget;
    }

    private int guiWidth() {
        return ClipboardChrome.guiWidth(this.width, MIN_GUI_WIDTH);
    }

    private int guiHeight() {
        return ClipboardChrome.guiHeight(this.height, MIN_GUI_HEIGHT);
    }

    @Override
    protected void init() {
        super.init();
        rebuild();
    }

    private void rebuild() {
        this.clearWidgets();
        rowY.clear();

        int left = (this.width - guiWidth()) / 2;
        int top = (this.height - guiHeight()) / 2;
        int contentX = left + PADDING + ClipboardChrome.MAIN_BORDER + 2;
        int contentY = top + PADDING + ClipboardChrome.MAIN_BORDER + 18;
        int btnY = top + guiHeight() - PADDING - ClipboardChrome.MAIN_BORDER - 22;

        listX = contentX;
        listY = contentY + 5;
        int y = listY;
        for (ColourKey key : ColourKey.values()) {
            rowY.put(key, y);
            Button row = new ClipboardButton(listX + SWATCH_SIZE + 4, y, 150, ROW_HEIGHT - 2,
                    Component.literal(key == selectedKey ? "> " : "").append(Component.translatable(key.translationKey())),
                    b -> {
                        selectedKey = key;
                        resetCachedHueForCurrentColour();
                        rebuild();
                    });
            this.addRenderableWidget(row);
            y += ROW_HEIGHT;
        }

        int editorX = listX + SWATCH_SIZE + 4 + 150 + 16;
        int editorY = contentY;

        // ClipboardButton centres its label at y + (height - 8) / 2. Match it so this label lines up
        // with the buttons' text.
        editorLabelY = editorY + (ROW_HEIGHT - 2 - 8) / 2;
        editorSwatchY = editorY + LABEL_TO_SWATCH;
        editorModeY = editorSwatchY + PREVIEW_HEIGHT + SECTION_GAP;
        editorSlider1Y = editorModeY + 14 + SECTION_GAP;
        editorSlider2Y = editorSlider1Y + 14 + SLIDER_GAP;
        editorSlider3Y = editorSlider2Y + 14 + SLIDER_GAP;
        editorHexY = editorSlider3Y + 14 + SECTION_GAP;
        editorResetY = editorHexY + 14 + SECTION_GAP;
        editorHintY = editorResetY + 14 + SECTION_GAP;

        int[] channels = channelsForMode(mode, ColourPalette.get(selectedKey));
        channelA = channels[0];
        channelB = channels[1];
        channelC = channels[2];

        Button modeButton = new ClipboardButton(editorX, editorModeY, 90, 14,
                Component.translatable("miforeman.colour_picker.mode_button", mode.name()), b -> {
            mode = mode.next();
            rebuild();
        });
        this.addRenderableWidget(modeButton);

        sliderA = new ChannelSlider(editorX, editorSlider1Y, SLIDER_WIDTH, 14, mode.a, channelA,
                candidate -> rgbForChannels(candidate, channelB, channelC),
                v -> {
                    channelA = v;
                    commitChannels();
                });
        this.addRenderableWidget(sliderA);

        sliderB = new ChannelSlider(editorX, editorSlider2Y, SLIDER_WIDTH, 14, mode.b, channelB,
                candidate -> rgbForChannels(channelA, candidate, channelC),
                v -> {
                    channelB = v;
                    commitChannels();
                });
        this.addRenderableWidget(sliderB);

        sliderC = new ChannelSlider(editorX, editorSlider3Y, SLIDER_WIDTH, 14, mode.c, channelC,
                candidate -> rgbForChannels(channelA, channelB, candidate),
                v -> {
                    channelC = v;
                    commitChannels();
                });
        this.addRenderableWidget(sliderC);

        hexBox = new EditBox(this.font, editorX, editorHexY, 100, 14, Component.translatable("miforeman.colour_picker.hex"));
        hexBox.setMaxLength(9);
        hexBox.setValue(ColourPalette.toHex(ColourPalette.get(selectedKey)));
        hexBox.setResponder(this::onHexChanged);
        this.addRenderableWidget(hexBox);
        hexInvalid = false;

        Button resetOne = new ClipboardButton(editorX, editorResetY, 100, 14,
                Component.translatable("miforeman.button.reset_this"), b -> {
            ColourPalette.resetToDefault(selectedKey);
            resetCachedHueForCurrentColour();
            rebuild();
        });
        this.addRenderableWidget(resetOne);

        Button resetAll = new ClipboardButton(contentX, btnY, 90, 16,
                Component.translatable("miforeman.button.reset_all"), b -> {
            for (ColourKey key : ColourKey.values()) {
                ColourPalette.resetToDefault(key);
            }
            resetCachedHueForCurrentColour();
            rebuild();
        });
        this.addRenderableWidget(resetAll);

        Button done = new ClipboardButton(contentX + guiWidth() - (PADDING + ClipboardChrome.MAIN_BORDER) * 2 - 4 - 90, btnY, 90, 16,
                Component.translatable("miforeman.button.done"), b -> {
                    ColourPalette.persist();
                    Minecraft.getInstance().setScreen(backTarget);
                });
        this.addRenderableWidget(done);
    }

    private int rgbForChannels(int a, int b, int c) {
        int[] rgb = switch (mode) {
            case RGB -> new int[]{a, b, c};
            case HSV -> ColourMath.rgbFromHsv(a, b, c);
            case HSL -> ColourMath.rgbFromHsl(a, b, c);
        };
        return (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
    }

    private int[] channelsForMode(ColourMode m, int argb) {
        int[] rgb = ColourMath.rgbFromArgb(argb);
        return switch (m) {
            case RGB -> rgb;
            case HSV -> {
                float[] hsv = ColourMath.hsvFromRgb(rgb[0], rgb[1], rgb[2]);
                boolean degenerate = hsv[1] < 1f || hsv[2] < 1f;
                float h = degenerate ? cachedHue : hsv[0];
                if (!degenerate) cachedHue = hsv[0];
                yield new int[]{Math.round(h), Math.round(hsv[1]), Math.round(hsv[2])};
            }
            case HSL -> {
                float[] hsl = ColourMath.hslFromRgb(rgb[0], rgb[1], rgb[2]);
                boolean degenerate = hsl[1] < 1f || hsl[2] < 1f || hsl[2] > 99f;
                float h = degenerate ? cachedHue : hsl[0];
                if (!degenerate) cachedHue = hsl[0];
                yield new int[]{Math.round(h), Math.round(hsl[1]), Math.round(hsl[2])};
            }
        };
    }

    private void resetCachedHueForCurrentColour() {
        int[] rgb = ColourMath.rgbFromArgb(ColourPalette.get(selectedKey));
        cachedHue = ColourMath.hsvFromRgb(rgb[0], rgb[1], rgb[2])[0];
    }

    private void commitChannels() {
        int rgb = rgbForChannels(channelA, channelB, channelC);
        if (mode != ColourMode.RGB) {
            cachedHue = channelA;
        }
        int alpha = (ColourPalette.get(selectedKey) >>> 24) & 0xFF;
        int argb = (alpha << 24) | rgb;
        String hex = ColourPalette.toHex(argb);
        ColourPalette.set(selectedKey, hex);
        // Goes through the hex box's responder so the other two sliders' gradients stay current.
        hexBox.setValue(hex);
    }

    private void onHexChanged(String value) {
        if (HEX_PATTERN.matcher(value).matches()) {
            hexInvalid = false;
            ColourPalette.set(selectedKey, value);
            int[] channels = channelsForMode(mode, ColourPalette.get(selectedKey));
            channelA = channels[0];
            channelB = channels[1];
            channelC = channels[2];
            if (sliderA != null) sliderA.setValueInt(channelA);
            if (sliderB != null) sliderB.setValueInt(channelB);
            if (sliderC != null) sliderC.setValueInt(channelC);
        } else {
            hexInvalid = true;
        }
    }

    @Override
    public void onClose() {
        ColourPalette.persist();
        Minecraft.getInstance().setScreen(backTarget);
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // No dimming, to match ClipboardScreen and ReviewMachinesScreen.
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        int left = (this.width - guiWidth()) / 2;
        int top = (this.height - guiHeight()) / 2;
        ClipboardChrome.drawBackground(guiGraphics, left, top, guiWidth(), guiHeight());

        int contentX = left + PADDING + ClipboardChrome.MAIN_BORDER + 2;
        int contentY = top + PADDING + ClipboardChrome.MAIN_BORDER + 2;
        guiGraphics.drawString(this.font, Component.translatable("miforeman.screen.colours.title"), contentX, contentY, COLOUR_TITLE, false);

        for (ColourKey key : ColourKey.values()) {
            int y = rowY.get(key);
            guiGraphics.fill(listX, y + 1, listX + SWATCH_SIZE, y + 1 + SWATCH_SIZE, 0xFF000000 | ColourPalette.get(key));
            guiGraphics.renderOutline(listX, y + 1, SWATCH_SIZE, SWATCH_SIZE, COLOUR_LABEL);
        }

        int editorX = listX + SWATCH_SIZE + 4 + 150 + 16;
        guiGraphics.drawString(this.font, Component.translatable(selectedKey.translationKey()), editorX, editorLabelY, COLOUR_LABEL, false);
        int previewArgb = ColourPalette.get(selectedKey);
        guiGraphics.fill(editorX, editorSwatchY, editorX + SWATCH_SIZE * 4, editorSwatchY + PREVIEW_HEIGHT, 0xFF000000 | previewArgb);
        guiGraphics.renderOutline(editorX, editorSwatchY, SWATCH_SIZE * 4, PREVIEW_HEIGHT, COLOUR_LABEL);

        if (hexInvalid) {
            guiGraphics.drawString(this.font, Component.translatable("miforeman.colour_picker.invalid_hex"), editorX, editorHintY, COLOUR_ERROR, false);
        } else {
            guiGraphics.drawString(this.font, Component.translatable("miforeman.colour_picker.hex_placeholder"), editorX, editorHintY, COLOUR_TEXT, false);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
