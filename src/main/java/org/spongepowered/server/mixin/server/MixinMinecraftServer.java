/*
 * This file is part of Sponge, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.server.mixin.server;

import net.minecraft.crash.CrashReport;
import net.minecraft.network.NetworkSystem;
import net.minecraft.network.ServerStatusResponse;
import net.minecraft.network.play.server.S03PacketTimeUpdate;
import net.minecraft.network.play.server.SPacketTimeUpdate;
import net.minecraft.profiler.Profiler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.management.PlayerList;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ITickable;
import net.minecraft.util.ReportedException;
import net.minecraft.util.Util;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.WorldServer;
import org.apache.logging.log4j.Logger;
import org.spongepowered.api.Server;
import org.spongepowered.api.event.SpongeEventFactory;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.cause.NamedCause;
import org.spongepowered.api.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import org.spongepowered.common.SpongeImpl;
import org.spongepowered.common.interfaces.IMixinMinecraftServer;
import org.spongepowered.common.text.SpongeTexts;
import org.spongepowered.server.SpongeVanilla;
import org.spongepowered.server.VanillaGame;
import org.spongepowered.server.world.VanillaDimensionManager;

import java.util.Hashtable;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.FutureTask;

@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer implements IMixinMinecraftServer {

    @Shadow @Final private static Logger logger;
    @Shadow @Final private List<ITickable> playersOnline;
    @Shadow @Final public Profiler theProfiler;
    @Shadow private PlayerList serverConfigManager;
    @Shadow private int tickCounter;
    @Shadow @Final protected Queue<FutureTask<?>> futureTaskQueue;

    @Shadow public abstract boolean getAllowNether();
    @Shadow public abstract NetworkSystem getNetworkSystem();

    private boolean skipServerStop;
    private final Hashtable<Integer, long[]> worldTickTimes = new Hashtable<>();

    private static final String DEDICATED_SERVER_INIT = "Lnet/minecraft/server/dedicated/DedicatedServer.<init> (Ljava/io/File;Lnet/minecraft/util/datafix/DataFixer;Lcom/mojang/authlib/yggdrasil/YggdrasilAuthenticationService;Lcom/mojang/authlib/minecraft/MinecraftSessionService;Lcom/mojang/authlib/GameProfileRepository;Lnet/minecraft/server/management/PlayerProfileCache;)V";

    /**
     * @author Minecrell
     * @reason Sets the server brand name to SpongeVanilla
     */
    @Overwrite
    public String getServerModName() {
        return SpongeVanilla.INSTANCE.getName();
    }

    /**
     * @author Minecrell
     * @reason Logs chat messages with legacy color codes to show colored
     *     messages in the console
     */
    @Overwrite
    public void addChatMessage(ITextComponent component) {
        logger.info(SpongeTexts.toLegacy(component));
    }

    @Override
    public Hashtable<Integer, long[]> getWorldTickTimes() {
        return this.worldTickTimes;
    }

    @Inject(method = "stopServer()V", at = @At("HEAD"), cancellable = true)
    private void preventDoubleStop(CallbackInfo ci) {
        if (this.skipServerStop) {
            ci.cancel();
        } else {
            // Prevent the server from stopping twice
            this.skipServerStop = true;
        }
    }

    @Inject(method = "stopServer", at = @At(value = "INVOKE", target = "Lorg/apache/logging/log4j/Logger;info(Ljava/lang/String;)V", ordinal = 0,
            shift = At.Shift.AFTER, remap = false))
    private void callServerStopping(CallbackInfo ci) {
        SpongeVanilla.INSTANCE.onServerStopping();
    }

    @Inject(method = "addFaviconToStatusResponse", at = @At("HEAD"), cancellable = true)
    private void onAddFaviconToStatusResponse(ServerStatusResponse response, CallbackInfo ci) {
        // Don't load favicon twice
        if (response.getFavicon() != null) {
            ci.cancel();
        }
    }

    @Inject(method = "stopServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/WorldServer;flush()V"),
            locals = LocalCapture.CAPTURE_FAILHARD)
    private void callWorldUnload(CallbackInfo ci, int i, WorldServer worldserver) {
        SpongeImpl.postEvent(SpongeEventFactory.createUnloadWorldEvent(Cause.of(NamedCause.source(this)), (World) worldserver));
    }

    @Inject(method = "main", at = @At(value = "INVOKE", target = DEDICATED_SERVER_INIT, shift = At.Shift.AFTER), locals = LocalCapture.PRINT)
    public void onInitDedicatedServer(String[] ags, CallbackInfo ci, int some, int random, int locals, int here, DedicatedServer server) {
        ((VanillaGame) SpongeImpl.getGame()).setServer((Server) server);
    }
    /**
     * @author Zidane
     * @reason Handles ticking the additional worlds loaded by Sponge.
     */
    @Overwrite
    public void updateTimeLightAndEntities() {
        this.theProfiler.startSection("jobs");

        synchronized (this.futureTaskQueue) {
            while (!this.futureTaskQueue.isEmpty()) {
                Util.runTask(this.futureTaskQueue.poll(), logger);
            }
        }

        this.theProfiler.endStartSection("levels");

        // Sponge start - Iterate over all our dimensions
        Integer[] ids = VanillaDimensionManager.getIDs(this.tickCounter % 200 == 0);
        for (int j = 0; j < ids.length; ++j) {
            int id = ids[j];
            // Sponge end
            long i = System.nanoTime();

            if (j == 0 || this.getAllowNether()) {
                // Sponge start - Get world from our dimension manager
                WorldServer worldserver = VanillaDimensionManager.getWorldFromDimId(id);
                // Sponge end
                this.theProfiler.startSection(worldserver.getWorldInfo().getWorldName());

                if (this.tickCounter % 20 == 0) {
                    this.theProfiler.startSection("timeSync");
                    this.serverConfigManager.sendPacketToAllPlayersInDimension(
                            new SPacketTimeUpdate(worldserver.getTotalWorldTime(), worldserver.getWorldTime(),
                                    worldserver.getGameRules().getBoolean("doDaylightCycle")), worldserver.provider.func_186058_p().func_186068_a());
                    this.theProfiler.endSection();
                }

                this.theProfiler.startSection("tick");

                try {
                    worldserver.tick();
                } catch (Throwable throwable1) {
                    CrashReport crashreport = CrashReport.makeCrashReport(throwable1, "Exception ticking world");
                    worldserver.addWorldInfoToCrashReport(crashreport);
                    throw new ReportedException(crashreport);
                }

                try {
                    worldserver.updateEntities();
                } catch (Throwable throwable) {
                    CrashReport crashreport1 = CrashReport.makeCrashReport(throwable, "Exception ticking world entities");
                    worldserver.addWorldInfoToCrashReport(crashreport1);
                    throw new ReportedException(crashreport1);
                }

                this.theProfiler.endSection();
                this.theProfiler.startSection("tracker");
                worldserver.getEntityTracker().updateTrackedEntities();
                this.theProfiler.endSection();
                this.theProfiler.endSection();
            }

            // Sponge start - Write tick times to our custom map
            this.worldTickTimes.get(id)[this.tickCounter % 100] = System.nanoTime() - i;
            // Sponge end
        }

        // Sponge start - Unload requested worlds
        this.theProfiler.endStartSection("dim_unloading");
        VanillaDimensionManager.unloadWorlds(this.worldTickTimes);
        // Sponge end

        this.theProfiler.endStartSection("connection");
        this.getNetworkSystem().networkTick();
        this.theProfiler.endStartSection("players");
        this.serverConfigManager.onTick();
        this.theProfiler.endStartSection("tickables");

        for (int k = 0; k < this.playersOnline.size(); ++k) {
            this.playersOnline.get(k).update();
        }

        this.theProfiler.endSection();
    }

}
