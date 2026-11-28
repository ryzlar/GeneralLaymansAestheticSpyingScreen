package ryzlar.glass.decoy;

import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * The copy of a player a Nano Decoy projects, made of nano cubes (see the client's NanoDecoyFigureRenderer). It builds up
 * out of the decoy's lens and stands where it was projected, facing the way the decoy was thrown; for {@link #LIFE_TICKS} it draws the monsters around
 * it, and the mobs angry at its owner, away from its owner. Nothing hurts it: hit, it glitches and drops a few cubes.
 * Then it collapses from the top down. It carries its owner's name, like a player.
 */
public class NanoDecoyFigure extends LivingEntity
{
    /** How long it takes to build up, how long it lures, and how long it takes to collapse (ticks). */
    public static final int BUILD_TICKS = 28, LIFE_TICKS = 13 * 20, COLLAPSE_TICKS = 28;
    public static final int TOTAL_TICKS = BUILD_TICKS + LIFE_TICKS + COLLAPSE_TICKS;
    /** How far it draws mobs from (blocks), and how often it calls them (ticks). */
    private static final double LURE_RANGE = 16D;
    private static final int LURE_EVERY = 10;
    /** The event sent to clients when it is hit: they make it glitch. */
    public static final byte HIT_EVENT = 77;

    private static final EntityDataAccessor<String> OWNER = SynchedEntityData.defineId(NanoDecoyFigure.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Long> BORN = SynchedEntityData.defineId(NanoDecoyFigure.class, EntityDataSerializers.LONG);

    private float heading;
    private long lastHit = Long.MIN_VALUE;
    /** On the client: the game time it was last hit. */
    public long clientHit = Long.MIN_VALUE / 2;

    public NanoDecoyFigure(EntityType<? extends NanoDecoyFigure> type, Level level)
    {
        super(type, level);
    }

    public static AttributeSupplier.Builder attributes()
    {
        return LivingEntity.createLivingAttributes().add(Attributes.KNOCKBACK_RESISTANCE, 1D);
    }

    /** A copy of this player, here, facing the way given. */
    public static NanoDecoyFigure of(ServerLevel level, @Nullable Player owner, double x, double y, double z, float yaw)
    {
        NanoDecoyFigure figure = new NanoDecoyFigure(NanoDecoy.FIGURE, level);
        figure.snapTo(x, y, z, yaw, 0F);
        figure.heading = yaw;
        figure.setYBodyRot(yaw);
        figure.setYHeadRot(yaw);
        figure.entityData.set(BORN, level.getGameTime());
        if(owner != null)
        {
            figure.entityData.set(OWNER, owner.getUUID().toString());
            figure.setCustomName(Component.literal(owner.getGameProfile().getName()));
            figure.setCustomNameVisible(true);
        }
        return figure;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder)
    {
        super.defineSynchedData(builder);
        builder.define(OWNER, "");
        builder.define(BORN, 0L);
    }

    /** Whose copy it is (for the skin): their id, or null. */
    @Nullable
    public UUID ownerId()
    {
        try
        {
            String id = entityData.get(OWNER);
            return id.isEmpty() ? null : UUID.fromString(id);
        }
        catch(IllegalArgumentException e)
        {
            return null;
        }
    }

    /** How long it has been there (ticks, with the partial tick). */
    public float age(float partialTick)
    {
        return level().getGameTime() - entityData.get(BORN) + partialTick;
    }

    /** True while it draws mobs (built up, not collapsing yet). */
    public boolean luring()
    {
        return luring(level().getGameTime() - entityData.get(BORN));
    }

    private boolean luring(long age)
    {
        return age >= BUILD_TICKS && age < BUILD_TICKS + LIFE_TICKS;
    }

    @Override
    public void tick()
    {
        long age = level().getGameTime() - entityData.get(BORN);
        //It stands where it was projected, facing the way the decoy was thrown.
        if(!level().isClientSide)
        {
            setYRot(heading);
            setYBodyRot(heading);
            setYHeadRot(heading);
            zza = 0F;
            xxa = 0F;
        }
        super.tick();
        if(level().isClientSide)
        {
            return;
        }
        if(luring(age) && (age - BUILD_TICKS) % LURE_EVERY == 0)
        {
            lure();
        }
        if(age >= TOTAL_TICKS)
        {
            letGo();
            discard();
        }
    }

    /** Monsters near it go for it; so do the mobs angry at its owner (wolves, endermen, piglins, golems, the warden). */
    private void lure()
    {
        Player owner = ownerId() == null ? null : level().getPlayerByUUID(ownerId());
        for(Mob mob : level().getEntitiesOfClass(Mob.class, getBoundingBox().inflate(LURE_RANGE), Mob::isAlive))
        {
            boolean angry = owner != null && (mob.getTarget() == owner
                    || mob instanceof NeutralMob neutral && owner.getUUID().equals(neutral.getPersistentAngerTarget())
                    || attackTarget(mob) == owner);
            if(!(mob instanceof Enemy) && !angry)
            {
                continue;
            }
            if(mob instanceof Warden warden)
            {
                warden.increaseAngerAt(this, 150, false);
            }
            if(mob.getTarget() != this)
            {
                mob.setTarget(this);
            }
            if(mob.getBrain().checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED))
            {
                mob.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, this);
            }
        }
    }

    /** Gone: the mobs it drew stop going for it (they find their own way back to its owner). */
    private void letGo()
    {
        for(Mob mob : level().getEntitiesOfClass(Mob.class, getBoundingBox().inflate(LURE_RANGE * 2D)))
        {
            if(mob.getTarget() == this)
            {
                mob.setTarget(null);
            }
            if(attackTarget(mob) == this)
            {
                mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
            }
        }
    }

    /** What a mob with a brain (piglins, hoglins, the warden) is set on attacking, or null (also without such a brain). */
    @Nullable
    private static LivingEntity attackTarget(Mob mob)
    {
        if(!mob.getBrain().checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED))
        {
            return null;
        }
        return mob.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
    }

    /** Nothing hurts it: hit, it glitches (at most every few ticks). */
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount)
    {
        long now = level.getGameTime();
        if(now - lastHit >= 4L)
        {
            lastHit = now;
            level.broadcastEntityEvent(this, HIT_EVENT);
        }
        return false;
    }

    @Override
    public void handleEntityEvent(byte id)
    {
        if(id == HIT_EVENT)
        {
            clientHit = level().getGameTime();
        }
        else
        {
            super.handleEntityEvent(id);
        }
    }

    @Override
    public HumanoidArm getMainArm()
    {
        return HumanoidArm.RIGHT;
    }

    @Override
    public boolean isPushable()
    {
        return false;
    }

    @Override
    protected void doPush(net.minecraft.world.entity.Entity entity)
    {
    }

    @Nullable
    @Override
    protected SoundEvent getHurtSound(DamageSource source)
    {
        return null;
    }

    @Nullable
    @Override
    protected SoundEvent getDeathSound()
    {
        return null;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state)
    {
    }

    @Override
    public boolean causeFallDamage(double distance, float multiplier, DamageSource source)
    {
        return false;
    }

    /** A decoy is gone after a few seconds: it is not kept in the world's save. */
    @Override
    public boolean shouldBeSaved()
    {
        return false;
    }
}
