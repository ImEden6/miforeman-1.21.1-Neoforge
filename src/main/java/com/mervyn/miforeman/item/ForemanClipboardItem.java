package com.mervyn.miforeman.item;

import com.mervyn.miforeman.goal.MachineLinkHistory;
import com.mervyn.miforeman.goal.ProductionGoal;
import com.mervyn.miforeman.network.MachineLinkSyncPayload;
import com.mervyn.miforeman.registry.ModComponents;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

public class ForemanClipboardItem extends Item {
    public ForemanClipboardItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }

        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof aztech.modern_industrialization.machines.MachineBlockEntity) {
            if (!level.isClientSide) {
                ItemStack stack = context.getItemInHand();
                ProductionGoal goal = stack.get(ModComponents.PRODUCTION_GOAL.get());
                if (goal == null) {
                    player.sendSystemMessage(Component.literal("No active production goal on this clipboard! Set a goal first.").withStyle(ChatFormatting.RED));
                    return InteractionResult.SUCCESS;
                }

                GlobalPos globalPos = GlobalPos.of(level.dimension(), pos);
                List<GlobalPos> linked = new ArrayList<>(goal.linkedMachines());
                boolean wasLinked = linked.contains(globalPos);
                MachineLinkHistory history;
                ProductionGoal updatedGoal;
                if (wasLinked) {
                    linked.remove(globalPos);
                    history = goal.machineLinkHistory().withToggle(globalPos, true, false);
                    updatedGoal = goal.withLinkedMachines(linked, history);
                    player.sendSystemMessage(Component.literal("Unlinked machine at " + pos.toShortString()).withStyle(ChatFormatting.YELLOW));
                } else {
                    linked.add(globalPos);
                    history = goal.machineLinkHistory().withToggle(globalPos, false, true);
                    // manual link always clears a sticky rejection
                    updatedGoal = goal.withLinkedMachines(linked, history).withoutRejectedMachine(globalPos);
                    player.sendSystemMessage(Component.literal("Linked machine at " + pos.toShortString()).withStyle(ChatFormatting.GREEN));
                }
                stack.set(ModComponents.PRODUCTION_GOAL.get(), updatedGoal);

                if (player instanceof ServerPlayer serverPlayer) {
                    boolean nowLinked = !wasLinked;
                    PacketDistributor.sendToPlayer(serverPlayer, new MachineLinkSyncPayload(globalPos, nowLinked));
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }

        return super.useOn(context);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack itemstack = player.getItemInHand(hand);
        if (level.isClientSide) {
            com.mervyn.miforeman.client.ClientAccess.openClipboardScreen(itemstack, hand);
        }
        return InteractionResultHolder.sidedSuccess(itemstack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        ProductionGoal goal = stack.get(ModComponents.PRODUCTION_GOAL.get());
        if (goal == null) {
            tooltip.add(Component.literal("No active production goal").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        } else {
            tooltip.add(Component.literal("Goal: ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(goal.name()).withStyle(ChatFormatting.WHITE)));
            tooltip.add(Component.literal("Target: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(goal.targetId().toString()).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" (" + goal.type().getSerializedName() + ")").withStyle(ChatFormatting.DARK_GRAY)));
            tooltip.add(Component.literal("Rate: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(String.format("%.2f / min", goal.rate())).withStyle(ChatFormatting.GREEN)));
        }
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
