package org.spongepowered.server.mixin.server;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.world.Teleporter;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = ServerConfigurationManager.class, priority = 999)
public abstract class MixinServerConfigurationManager {

    // Note: This is needed to add Forge NMS-only method so common can overwrite
    public void transferPlayerToDimension(EntityPlayerMP playerIn, int dimension, Teleporter teleporter) {

    }

    public void transferEntityToWorld(Entity entityIn, int fromDimensionId, WorldServer fromWorld, WorldServer toWorld, net.minecraft.world.Teleporter teleporter) {

    }
}
