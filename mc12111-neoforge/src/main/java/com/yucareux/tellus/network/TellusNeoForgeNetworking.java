package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.TellusClient;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Payload registration adapted to NeoForge 1.21.11's required client handlers. */
public final class TellusNeoForgeNetworking {
   private TellusNeoForgeNetworking() {
   }

   public static void registerPayloadHandlers(RegisterPayloadHandlersEvent event) {
      PayloadRegistrar registrar = event.registrar("1");
      registrar.playToServer(GeoTpTeleportPayload.TYPE, GeoTpTeleportPayload.CODEC, Tellus::handleGeoTeleport);
      registrar.playToServer(ManagedTerrainViewPayload.TYPE, ManagedTerrainViewPayload.CODEC, Tellus::handleManagedTerrainView);

      if (FMLEnvironment.getDist() == Dist.CLIENT) {
         registrar.playToClient(GeoTpOpenMapPayload.TYPE, GeoTpOpenMapPayload.CODEC, TellusClient::handleOpenMapPayload);
         registrar.playToClient(TellusWeatherPayload.TYPE, TellusWeatherPayload.CODEC, TellusClient::handleWeatherPayload);
         registrar.playToClient(
            ManagedTerrainStatusPayload.TYPE, ManagedTerrainStatusPayload.CODEC, TellusClient::handleManagedTerrainStatusPayload
         );
      } else {
         // A dedicated-server classpath has no client handlers, but still needs matching payload registrations.
         registrar.playToClient(GeoTpOpenMapPayload.TYPE, GeoTpOpenMapPayload.CODEC, (payload, context) -> {});
         registrar.playToClient(TellusWeatherPayload.TYPE, TellusWeatherPayload.CODEC, (payload, context) -> {});
         registrar.playToClient(ManagedTerrainStatusPayload.TYPE, ManagedTerrainStatusPayload.CODEC, (payload, context) -> {});
      }
   }

   public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
      PacketDistributor.sendToPlayer(player, payload);
   }
}
