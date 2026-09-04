package com.mervyn.miforeman.network;

import com.mervyn.miforeman.MIForeman;
import com.mervyn.miforeman.goal.FailureReason;
import com.mervyn.miforeman.goal.MachineStatus;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

public record LiveMonitoringPayload(List<LiveMonitoringPayload.MachineStatusData> machines) implements CustomPacketPayload {
    public static final Type<LiveMonitoringPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "live_monitoring"));

    // 7 fields exceeds StreamCodec.composite's max arity (6), so this is coded by hand --
    // same pattern as ClipboardUiState.STREAM_CODEC.
    public record MachineStatusData(GlobalPos pos, MachineStatus status, FailureReason reason, double actualRate,
                                     double disposalRatio, ResourceLocation machineId, Optional<ResourceLocation> recipeId) {
        public static final StreamCodec<RegistryFriendlyByteBuf, MachineStatusData> STREAM_CODEC =
                new StreamCodec<>() {
                    @Override
                    public MachineStatusData decode(RegistryFriendlyByteBuf buf) {
                        GlobalPos pos = GlobalPos.STREAM_CODEC.decode(buf);
                        MachineStatus status = MachineStatus.valueOf(ByteBufCodecs.STRING_UTF8.decode(buf));
                        FailureReason reason = FailureReason.valueOf(ByteBufCodecs.STRING_UTF8.decode(buf));
                        double actualRate = ByteBufCodecs.DOUBLE.decode(buf);
                        double disposalRatio = ByteBufCodecs.DOUBLE.decode(buf);
                        ResourceLocation machineId = ResourceLocation.STREAM_CODEC.decode(buf);
                        Optional<ResourceLocation> recipeId = ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC).decode(buf);
                        return new MachineStatusData(pos, status, reason, actualRate, disposalRatio, machineId, recipeId);
                    }

                    @Override
                    public void encode(RegistryFriendlyByteBuf buf, MachineStatusData value) {
                        GlobalPos.STREAM_CODEC.encode(buf, value.pos());
                        ByteBufCodecs.STRING_UTF8.encode(buf, value.status().name());
                        ByteBufCodecs.STRING_UTF8.encode(buf, value.reason().name());
                        ByteBufCodecs.DOUBLE.encode(buf, value.actualRate());
                        ByteBufCodecs.DOUBLE.encode(buf, value.disposalRatio());
                        ResourceLocation.STREAM_CODEC.encode(buf, value.machineId());
                        ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC).encode(buf, value.recipeId());
                    }
                };
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, LiveMonitoringPayload> STREAM_CODEC = StreamCodec.composite(
            MachineStatusData.STREAM_CODEC.apply(ByteBufCodecs.list()),
            LiveMonitoringPayload::machines,
            LiveMonitoringPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
