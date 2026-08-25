package com.mervyn.miforeman.client.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;
import java.util.function.IntUnaryOperator;

/**
 * Color channel slider widget rendering a gradient track.
 */
public class ChannelSlider extends AbstractSliderButton {
    public record Channel(String label, int min, int max) {
    }

    private static final int HANDLE_WIDTH = 8;
    private static final int COLOUR_TEXT = 0xFFFFFFFF;
    private static final int COLOUR_TRACK_BORDER = 0xFF6B5030;
    private static final int COLOUR_HANDLE_BORDER = 0xFF3A2A18;

    private final Channel channel;
    private final IntUnaryOperator colourAt;
    private final IntConsumer onChange;

    public ChannelSlider(int x, int y, int width, int height, Channel channel, int initialValue,
                          IntUnaryOperator colourAt, IntConsumer onChange) {
        super(x, y, width, height, Component.empty(), normalize(channel, initialValue));
        this.channel = channel;
        this.colourAt = colourAt;
        this.onChange = onChange;
        updateMessage();
    }

    private static double normalize(Channel channel, int v) {
        return (v - channel.min()) / (double) (channel.max() - channel.min());
    }

    public int currentInt() {
        return channel.min() + (int) Math.round(value * (channel.max() - channel.min()));
    }

    /** Sets slider position without triggering change callbacks. */
    public void setValueInt(int v) {
        this.value = normalize(channel, v);
        updateMessage();
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal(channel.label() + ": " + currentInt()));
    }

    @Override
    protected void applyValue() {
        onChange.accept(currentInt());
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int trackX = getX() + HANDLE_WIDTH / 2;
        int trackWidth = getWidth() - HANDLE_WIDTH;
        int range = channel.max() - channel.min();
        for (int px = 0; px < trackWidth; px++) {
            int candidate = channel.min() + range * px / Math.max(1, trackWidth - 1);
            int colour = 0xFF000000 | colourAt.applyAsInt(candidate);
            guiGraphics.fill(trackX + px, getY() + 2, trackX + px + 1, getY() + getHeight() - 2, colour);
        }
        guiGraphics.renderOutline(getX(), getY() + 2, getWidth(), getHeight() - 4, COLOUR_TRACK_BORDER);

        int handleX = getX() + (int) (value * trackWidth);
        guiGraphics.fill(handleX, getY(), handleX + HANDLE_WIDTH, getY() + getHeight(), COLOUR_TEXT);
        guiGraphics.renderOutline(handleX, getY(), HANDLE_WIDTH, getHeight(), COLOUR_HANDLE_BORDER);

        guiGraphics.drawCenteredString(Minecraft.getInstance().font, getMessage(),
                getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, COLOUR_TEXT);
    }
}
