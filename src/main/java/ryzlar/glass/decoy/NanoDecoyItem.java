package ryzlar.glass.decoy;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Right-click: throws a Nano Decoy (used up), and the next one can only be thrown after a while. */
public class NanoDecoyItem extends Item
{
    /** How long after a throw the next decoy can be thrown (ticks). */
    public static final int COOLDOWN_TICKS = 20 * 20;
    /** How hard it is thrown (a snowball is 1.5). */
    private static final float SPEED = 0.9F;

    public NanoDecoyItem(Properties properties)
    {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand)
    {
        ItemStack stack = player.getItemInHand(hand);
        if(level instanceof ServerLevel)
        {
            NanoDecoyBall ball = new NanoDecoyBall(NanoDecoy.BALL, level);
            ball.setOwner(player);
            ball.setPos(player.getX(), player.getEyeY() - 0.1D, player.getZ());
            ball.shootFromRotation(player, player.getXRot(), player.getYRot(), 0F, SPEED, 1F);
            ball.setHeading(player.getYRot());
            level.addFreshEntity(ball);
        }
        player.getCooldowns().addCooldown(stack, COOLDOWN_TICKS);
        player.awardStat(Stats.ITEM_USED.get(this));
        stack.consume(1, player);
        return InteractionResult.SUCCESS;
    }
}
