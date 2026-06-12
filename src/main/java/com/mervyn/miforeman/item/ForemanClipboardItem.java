package com.mervyn.miforeman.item;

import com.mervyn.miforeman.goal.ProductionGoal;
import com.mervyn.miforeman.registry.ModComponents;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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

                List<BlockPos> linked = new ArrayList<>(goal.linkedMachines());
                if (linked.contains(pos)) {
                    linked.remove(pos);
                    ProductionGoal updatedGoal = new ProductionGoal(
                            goal.name(),
                            goal.type(),
                            goal.targetId(),
                            goal.rate(),
                            goal.recipeSelections(),
                            goal.plan(),
                            goal.perHour(),
                            goal.threshold(),
                            linked
                    );
                    stack.set(ModComponents.PRODUCTION_GOAL.get(), updatedGoal);
                    player.sendSystemMessage(Component.literal("Unlinked machine at " + pos.toShortString()).withStyle(ChatFormatting.YELLOW));
                } else {
                    linked.add(pos);
                    ProductionGoal updatedGoal = new ProductionGoal(
                            goal.name(),
                            goal.type(),
                            goal.targetId(),
                            goal.rate(),
                            goal.recipeSelections(),
                            goal.plan(),
                            goal.perHour(),
                            goal.threshold(),
                            linked
                    );
                    stack.set(ModComponents.PRODUCTION_GOAL.get(), updatedGoal);
                    player.sendSystemMessage(Component.literal("Linked machine at " + pos.toShortString()).withStyle(ChatFormatting.GREEN));
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
            Runnable r = () -> com.mervyn.miforeman.client.ClientAccess.openClipboardScreen(itemstack);
            r.run();
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
