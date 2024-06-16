/*
 * Copyright (C) 2023 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.protocol.netty;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.PlatformDependent;
import java.util.Queue;
import org.jetbrains.annotations.NotNull;

/**
 * Queues up any pending PLAY packets while the client is in the CONFIG state.
 *
 * <p>Much of the Velocity API (i.e. chat messages) utilize PLAY packets, however the client is
 * incapable of receiving these packets during the CONFIG state. Certain events such as the
 * ServerPreConnectEvent may be called during this time, and we need to ensure that any API that
 * uses these packets will work as expected.
 *
 * <p>This handler will queue up any packets that are sent to the client during this time, and send
 * them once the client has (re)entered the PLAY state.
 */
public class PlayPacketQueueInboundHandler extends ChannelDuplexHandler {

  private final StateRegistry.PacketRegistry.ProtocolRegistry registry;
  private final Queue<Object> queue = PlatformDependent.newMpscQueue();

  /**
   * Provides registries for client &amp; server bound packets.
   *
   * @param version the protocol version
   */
  public PlayPacketQueueInboundHandler(ProtocolVersion version, ProtocolUtils.Direction direction) {
    this.registry = StateRegistry.CONFIG.getProtocolRegistry(direction, version);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof final MinecraftPacket packet) {
      // If the packet exists in the CONFIG state, we want to always
      // ensure that it gets handled by the current handler
      if (this.registry.containsPacket(packet)) {
        ctx.fireChannelRead(msg);
        return;
      }
    }

    // Otherwise, queue the packet
    this.queue.offer(msg);
  }

  @Override
  public void channelInactive(@NotNull ChannelHandlerContext ctx) throws Exception {
    this.releaseQueue(ctx, false);

    super.channelInactive(ctx);
  }

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
    this.releaseQueue(ctx, ctx.channel().isActive());
  }

  private void releaseQueue(ChannelHandlerContext ctx, boolean active) {
    // Handle all the queued packets
    Object msg;
    while ((msg = this.queue.poll()) != null) {
      if (active) {
        ctx.fireChannelRead(msg);
      } else {
        ReferenceCountUtil.release(msg);
      }
    }
  }
}
