package ryzlar.glass.client.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;

/**
 * A nano pixel flying from one place to another: from {@code (x, y, z) + (dx, dy, dz)} to {@code (x, y, z)} over its
 * life, fast at first and settling in (a cloaked build streaming into its projector, and back out of it). Glows at full
 * brightness, in GLASS's cyan or white, and shrinks as it arrives.
 */
public class NanoParticle extends TextureSheetParticle
{
    private final double toX, toY, toZ;
    private final double fromX, fromY, fromZ;
    private final float size;

    protected NanoParticle(ClientLevel level, double x, double y, double z, double dx, double dy, double dz)
    {
        super(level, x + dx, y + dy, z + dz);
        this.toX = x;
        this.toY = y;
        this.toZ = z;
        this.fromX = x + dx;
        this.fromY = y + dy;
        this.fromZ = z + dz;
        this.xd = 0D;
        this.yd = 0D;
        this.zd = 0D;
        this.hasPhysics = false;
        this.gravity = 0F;
        this.lifetime = 14 + random.nextInt(12);
        this.size = 0.05F + random.nextFloat() * 0.05F;
        this.quadSize = size;
        if(random.nextInt(4) == 0)
        {
            setColor(0.85F, 0.98F, 1F); //white
        }
        else
        {
            setColor(0.24F, 0.9F, 1F); //cyan
        }
        setAlpha(0.95F);
    }

    @Override
    public ParticleRenderType getRenderType()
    {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    public int getLightColor(float partialTick)
    {
        return 0xF000F0; //glows
    }

    @Override
    public float getQuadSize(float partialTick)
    {
        float t = Mth.clamp((age + partialTick) / lifetime, 0F, 1F);
        return size * (1F - t * t * 0.7F);
    }

    @Override
    public void tick()
    {
        xo = x;
        yo = y;
        zo = z;
        if(age++ >= lifetime)
        {
            remove();
            return;
        }
        float t = (float)age / lifetime;
        float eased = 1F - (1F - t) * (1F - t) * (1F - t); //fast, then settling
        x = Mth.lerp(eased, fromX, toX);
        y = Mth.lerp(eased, fromY, toY) + Math.sin(t * Math.PI) * 0.4D; //a little arc on the way
        z = Mth.lerp(eased, fromZ, toZ);
    }

    public static class Provider implements ParticleProvider<SimpleParticleType>
    {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites)
        {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz)
        {
            NanoParticle particle = new NanoParticle(level, x, y, z, dx, dy, dz);
            particle.pickSprite(sprites);
            return particle;
        }
    }
}
