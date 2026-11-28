package ryzlar.glass.decoy;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A thrown Nano Decoy. It flies, tumbling, and stops where it lands; it opens into a hexagonal base with a floating ring
 * over it (see the client's NanoDecoyRenderer) and projects a
 * copy of its thrower ({@link NanoDecoyFigure}) for as long as that lives, then the lens sinks back and it fizzles out. Both sides run its flight (the server's
 * position is sent every tick); only the server decides when it lands.
 */
public class NanoDecoyBall extends Projectile
{
    /** How long it takes to open into its hexagons, how long it then projects, and how long it takes to go (ticks). */
    public static final int OPEN_TICKS = 16, STAND_TICKS = NanoDecoyFigure.TOTAL_TICKS, CLOSE_TICKS = 14;
    /** How far it tumbles per block it flies (radians). */
    public static final float TUMBLE = 5F;

    private static final double GRAVITY = 0.045D;
    /** It lands at the latest this long after being thrown (ticks). */
    private static final int MAX_FLIGHT = 200;

    /** The game time it landed (-1: still flying), and the way it faces (the way it was thrown). */
    private static final EntityDataAccessor<Long> LANDED = SynchedEntityData.defineId(NanoDecoyBall.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Float> HEADING = SynchedEntityData.defineId(NanoDecoyBall.class, EntityDataSerializers.FLOAT);

    private static final DustParticleOptions TRAIL = new DustParticleOptions(0x3FD8FF, 0.6F);

    private int age;
    /** On the client: whether its landing burst was shown, and whether its hexagons fell apart. */
    private boolean burst, dissolved;
    private static final DustParticleOptions PLATE_DUST = new DustParticleOptions(0x9AA6B8, 0.7F);
    private static final DustParticleOptions RIM_DUST = new DustParticleOptions(0x5FF4FF, 0.9F);
    /** The hexagons (blocks): the base's apothem, the ring's, and the ring's height (see the client's NanoDecoyRenderer). */
    private static final double BASE_APOTHEM = 6D / 16D, RING_APOTHEM = 3.6D / 16D, RING_HEIGHT = 6D / 16D;
    /** On the client: how far it has tumbled (radians), last tick and now. */
    public float roll, rollO;

    public NanoDecoyBall(EntityType<? extends NanoDecoyBall> type, Level level)
    {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder)
    {
        builder.define(LANDED, -1L);
        builder.define(HEADING, 0F);
    }

    public void setHeading(float yaw)
    {
        entityData.set(HEADING, yaw);
    }

    /** The way it faces (yaw, degrees): the way it was thrown. */
    public float heading()
    {
        return entityData.get(HEADING);
    }

    /** The game time it landed, or -1 while it still flies. */
    public long landedAt()
    {
        return entityData.get(LANDED);
    }

    @Override
    protected double getDefaultGravity()
    {
        return GRAVITY;
    }

    @Override
    public void tick()
    {
        super.tick();
        age++;
        rollO = roll;
        if(landedAt() < 0L)
        {
            fly();
        }
        else
        {
            stand();
        }
    }

    private void fly()
    {
        Vec3 v = getDeltaMovement().add(0D, -getGravity(), 0D);
        double fromX = getX(), fromY = getY(), fromZ = getZ();
        move(MoverType.SELF, v);
        double drag = isInWater() ? 0.8D : 0.99D;
        //Against a wall it drops straight down; on the ground it stops where it is.
        double vx = horizontalCollision ? 0D : v.x * drag, vz = horizontalCollision ? 0D : v.z * drag;
        setDeltaMovement(vx, v.y * drag, vz);
        if(level().isClientSide)
        {
            roll += (float)(Math.sqrt(distanceToSqr(fromX, fromY, fromZ)) * TUMBLE);
            //A trail of cyan sparks behind it.
            level().addParticle(ParticleTypes.ELECTRIC_SPARK, fromX, fromY + 0.2D, fromZ, 0D, 0D, 0D);
            level().addParticle(TRAIL, (fromX + getX()) / 2D, (fromY + getY()) / 2D + 0.2D, (fromZ + getZ()) / 2D, 0D, 0D, 0D);
            return;
        }
        if(verticalCollision && v.y < 0D || age > MAX_FLIGHT)
        {
            land();
        }
    }

    private void land()
    {
        setDeltaMovement(Vec3.ZERO);
        entityData.set(LANDED, level().getGameTime());
    }

    private void stand()
    {
        //Standing, it only falls if the ground under it goes.
        if(!onGround())
        {
            setDeltaMovement(0D, Math.max(getDeltaMovement().y - getGravity(), -0.5D), 0D);
            move(MoverType.SELF, getDeltaMovement());
        }
        long time = level().getGameTime() - landedAt();
        if(level().isClientSide)
        {
            //Landing: a burst of sparks out over the ground.
            if(!burst)
            {
                burst = true;
                for(int i = 0; i < (time < 5L ? 18 : 0); i++)
                {
                    double a = i / 18D * Math.PI * 2D;
                    level().addParticle(ParticleTypes.ELECTRIC_SPARK, getX(), getY() + 0.1D, getZ(), Math.cos(a) * 0.25D, 0.08D, Math.sin(a) * 0.25D);
                }
            }
            //Its time up, the hexagons fall apart into particles, in their own shape, drifting up and fading.
            if(!dissolved && time >= OPEN_TICKS + STAND_TICKS)
            {
                dissolved = true;
                if(time < OPEN_TICKS + STAND_TICKS + 3L)
                {
                    dissolve();
                }
            }
            return;
        }
        if(time == OPEN_TICKS && level() instanceof ServerLevel server)
        {
            //Open: it projects the copy of its thrower over its ring, facing the way it was thrown.
            Player owner = getOwner() instanceof Player player ? player : null;
            server.addFreshEntity(NanoDecoyFigure.of(server, owner, getX(), getY(), getZ(), heading()));
        }
        if(time >= OPEN_TICKS + STAND_TICKS + CLOSE_TICKS)
        {
            discard();
        }
    }

    /** The hexagons as particles: the plate's metal over its whole face, cyan along its edge and round the ring. */
    private void dissolve()
    {
        double turn = Math.toRadians(-heading());
        //The plate: metal dust over the hexagon, a little cyan in it.
        for(int i = 0; i < 70; i++)
        {
            double[] p = inHexagon(BASE_APOTHEM);
            emit(i % 4 == 0 ? RIM_DUST : PLATE_DUST, p[0], 0.06D, p[1], turn, 0.02D + random.nextDouble() * 0.03D);
        }
        //Its glowing edge, and the floating ring.
        for(int side = 0; side < 6; side++)
        {
            for(int j = 0; j < 7; j++)
            {
                double[] p = onSide(BASE_APOTHEM, side, j / 6D);
                emit(RIM_DUST, p[0], 0.1D, p[1], turn, 0.03D + random.nextDouble() * 0.03D);
                if(j % 2 == 0)
                {
                    double[] q = onSide(RING_APOTHEM, side, j / 6D);
                    emit(RIM_DUST, q[0], RING_HEIGHT, q[1], turn, 0.04D + random.nextDouble() * 0.04D);
                }
            }
        }
        for(int i = 0; i < 10; i++)
        {
            double[] p = inHexagon(BASE_APOTHEM);
            emit(ParticleTypes.ELECTRIC_SPARK, p[0], 0.2D, p[1], turn, 0.06D);
        }
    }

    private void emit(net.minecraft.core.particles.ParticleOptions particle, double x, double y, double z, double turn, double up)
    {
        double c = Math.cos(turn), s = Math.sin(turn);
        double wx = x * c + z * s, wz = -x * s + z * c;
        level().addParticle(particle, getX() + wx, getY() + y, getZ() + wz,
                wx * 0.04D + (random.nextDouble() - 0.5D) * 0.01D, up, wz * 0.04D + (random.nextDouble() - 0.5D) * 0.01D);
    }

    /** A random point inside a hexagon with this apothem, flat sides facing north and south (x, z). */
    private double[] inHexagon(double apothem)
    {
        double reach = apothem * 2D / Math.sqrt(3D);
        while(true)
        {
            double x = (random.nextDouble() * 2D - 1D) * reach, z = (random.nextDouble() * 2D - 1D) * reach;
            boolean inside = true;
            for(int k = 0; k < 3; k++)
            {
                double a = Math.toRadians(90D + 60D * k);
                inside &= Math.abs(x * Math.cos(a) + z * Math.sin(a)) <= apothem;
            }
            if(inside)
            {
                return new double[] { x, z };
            }
        }
    }

    /** A point along one side (0 to 5) of a hexagon with this apothem, at t (0 to 1) from one corner to the next. */
    private static double[] onSide(double apothem, int side, double t)
    {
        double a = Math.toRadians(90D + 60D * side);
        double half = apothem / Math.sqrt(3D);
        double along = (t * 2D - 1D) * half;
        return new double[] { Math.cos(a) * apothem - Math.sin(a) * along, Math.sin(a) * apothem + Math.cos(a) * along };
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount)
    {
        return false;
    }

    @Override
    public boolean isPickable()
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
