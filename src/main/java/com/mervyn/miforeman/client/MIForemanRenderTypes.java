package com.mervyn.miforeman.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import org.lwjgl.opengl.GL11;

import java.util.OptionalDouble;

/**
 * Custom {@link RenderType}s for {@link WorldHighlightRenderer}'s "ghost through blocks" effect:
 * each shape is drawn twice, once into the *_OUTSIDE_BLOCKS type (normal depth test -- only
 * visible where actually unobstructed) and once into the *_INSIDE_BLOCKS type (depth test flipped
 * to GL_GREATER -- draws only the currently-occluded portion, meant to be used at reduced
 * colour/alpha). This is the same dual-pass technique used by Minecolonies for its own see-through
 * overlays (references/minecolonies-1.21.1/.../worldevent/RenderTypes.java), confirmed against
 * the decompiled vanilla RenderType.LINES/DEBUG_FILLED_BOX composite states. Unlike Minecolonies'
 * version (which draws its "lines" as extruded quads via an external library), LINES_* here keep
 * vanilla's real POSITION_COLOR_NORMAL/LINES format so {@link net.minecraft.client.renderer.LevelRenderer#renderLineBox}
 * can write into them unmodified.
 */
public final class MIForemanRenderTypes {
    private static final RenderStateShard.DepthTestStateShard GREATER_DEPTH_TEST =
            new RenderStateShard.DepthTestStateShard(">", GL11.GL_GREATER);

    public static final RenderType LINES_OUTSIDE_BLOCKS = RenderType.create(
            "miforeman:highlight_lines_outside_blocks",
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES,
            1536,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                    .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.empty()))
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setOutputState(RenderStateShard.MAIN_TARGET)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false)
    );

    public static final RenderType LINES_INSIDE_BLOCKS = RenderType.create(
            "miforeman:highlight_lines_inside_blocks",
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES,
            1536,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                    .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.empty()))
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(GREATER_DEPTH_TEST)
                    .setOutputState(RenderStateShard.MAIN_TARGET)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false)
    );

    public static final RenderType FILL_OUTSIDE_BLOCKS = RenderType.create(
            "miforeman:highlight_fill_outside_blocks",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            1536,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setOutputState(RenderStateShard.MAIN_TARGET)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false)
    );

    public static final RenderType FILL_INSIDE_BLOCKS = RenderType.create(
            "miforeman:highlight_fill_inside_blocks",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            1536,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(GREATER_DEPTH_TEST)
                    .setOutputState(RenderStateShard.MAIN_TARGET)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false)
    );

    private MIForemanRenderTypes() {
    }
}
