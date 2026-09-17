package com.yucareux.tellus.mixin.client;

import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds Tellus diagnostics to the text that the F3 overlay actually renders.
 *
 * <p>The chunk-generator debug hook is not consumed by the NeoForge F3 overlay on every
 * supported Minecraft version.  Injecting immediately before the left-hand F3 text is drawn
 * makes the diagnostic independent of that vanilla hook.</p>
 */
@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayMixin {
   private static final String BLUE = "\u00A79";

   @Inject(method = "renderLines", at = @At("HEAD"))
   private void tellus$addRiverDebugLines(GuiGraphics graphics, List<String> lines, boolean leftSide, CallbackInfo ci) {
      if (!leftSide) {
         return;
      }

      lines.add(BLUE + "[Tellus river debug: active]");

      Minecraft minecraft = Minecraft.getInstance();
      Entity camera = minecraft.getCameraEntity();
      MinecraftServer server = minecraft.getSingleplayerServer();
      if (minecraft.level == null || camera == null) {
         lines.add(BLUE + "Tellus river: world/camera unavailable");
         return;
      }

      if (server == null) {
         lines.add(BLUE + "Tellus river: server diagnostics require the world host");
         return;
      }

      ServerLevel level = server.getLevel(minecraft.level.dimension());
      if (level == null) {
         lines.add(BLUE + "Tellus river: matching server level unavailable");
         return;
      }

      if (!(level.getChunkSource().getGenerator() instanceof EarthChunkGenerator generator)) {
         lines.add(BLUE + "Tellus river: active generator is not Tellus");
         return;
      }

      BlockPos pos = camera.blockPosition();
      int firstGeneratorLine = lines.size();
      generator.addDebugScreenInfo(lines, null, pos);
      for (int index = firstGeneratorLine; index < lines.size(); index++) {
         lines.set(index, BLUE + lines.get(index));
      }
   }
}
