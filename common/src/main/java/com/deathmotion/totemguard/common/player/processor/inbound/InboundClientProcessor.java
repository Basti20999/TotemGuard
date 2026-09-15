/*
 * This file is part of TotemGuard - https://github.com/Bram1903/TotemGuard
 * Copyright (C) 2026 Bram and contributors
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.deathmotion.totemguard.common.player.processor.inbound;

import com.deathmotion.totemguard.common.player.TGPlayer;
import com.deathmotion.totemguard.common.player.data.MarlowHandshake;
import com.deathmotion.totemguard.common.player.processor.ProcessorInbound;
import com.deathmotion.totemguard.common.util.ChatUtil;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

public class InboundClientProcessor extends ProcessorInbound {

    private static final String BRAND_CHANNEL = PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_13) ? "minecraft:brand" : "MC|Brand";
    private static final String REGISTER_CHANNEL = "minecraft:register";

    private static final String MARLOW_NAMESPACE = "marlowcrystal:";
    private static final String MARLOW_VERSION_CHANNEL = "marlowcrystal:version";
    private static final String MARLOW_CHALLENGE_CHANNEL = "marlowcrystal:challenge";
    private static final String MARLOW_RESPONSE_CHANNEL = "marlowcrystal:challenge_response";

    // The mod answers the challenge through ClientPlayNetworking.send, which throws until the client has a
    // local player. A challenge is therefore only sent once the client has produced a tick or movement packet,
    // and it is repeated a few times because the mod announces itself once per join and never retries.
    private static final int MARLOW_MAX_ATTEMPTS = 3;
    private static final long MARLOW_RETRY_MILLIS = 5_000L;

    private boolean hasBrand;
    private boolean marlowChallengePending;
    private int marlowAttempts;
    private long marlowChallengeSentAt;
    private int marlowChallengeId;

    public InboundClientProcessor(TGPlayer player) {
        super(player);
    }

    @Override
    public void handleInbound(PacketReceiveEvent event) {
        final PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Client.PLUGIN_MESSAGE) {
            WrapperPlayClientPluginMessage packet = new WrapperPlayClientPluginMessage(event);
            dispatch(packet.getChannelName(), packet.getData(), true);
        } else if (type == PacketType.Configuration.Client.PLUGIN_MESSAGE) {
            WrapperConfigClientPluginMessage packet = new WrapperConfigClientPluginMessage(event);
            dispatch(packet.getChannelName(), packet.getData(), false);
        } else if (event.getConnectionState() == ConnectionState.PLAY && isClientInGame(type)) {
            tickMarlowHandshake();
        }
    }

    private void dispatch(String channel, byte[] data, boolean play) {
        if (channel == null) return;

        if (!hasBrand && BRAND_CHANNEL.equals(channel)) {
            handleBrand(data);
            return;
        }
        if (player.isMarlowOptimizer()) return;

        if (REGISTER_CHANNEL.equals(channel)) {
            if (registersMarlowChannel(data)) announceMarlow();
            return;
        }
        if (!play) return;

        if (MARLOW_VERSION_CHANNEL.equals(channel)) {
            announceMarlow();
        } else if (MARLOW_RESPONSE_CHANNEL.equals(channel)) {
            if (player.getMarlowHandshake() != MarlowHandshake.CHALLENGED) return;
            if (data == null || data.length != 4) return;
            int received = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16) | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
            if (received != marlowChallengeId) return;
            marlowChallengePending = false;
            player.setMarlowHandshake(MarlowHandshake.VERIFIED);
        }
    }

    private static boolean registersMarlowChannel(byte[] data) {
        if (data == null || data.length == 0) return false;
        for (String registered : new String(data, StandardCharsets.UTF_8).split("\0")) {
            if (registered.startsWith(MARLOW_NAMESPACE)) return true;
        }
        return false;
    }

    private void announceMarlow() {
        MarlowHandshake state = player.getMarlowHandshake();
        if (state == MarlowHandshake.FAILED) return;
        if (state == MarlowHandshake.NONE) {
            player.setMarlowHandshake(MarlowHandshake.ANNOUNCED);
        }
        marlowAttempts = 0;
        marlowChallengePending = true;
    }

    private static boolean isClientInGame(PacketTypeCommon type) {
        return type == PacketType.Play.Client.CLIENT_TICK_END || WrapperPlayClientPlayerFlying.isFlying(type);
    }

    private void tickMarlowHandshake() {
        if (!marlowChallengePending) return;

        MarlowHandshake state = player.getMarlowHandshake();
        if (state == MarlowHandshake.CHALLENGED && System.currentTimeMillis() - marlowChallengeSentAt < MARLOW_RETRY_MILLIS) return;

        if (marlowAttempts >= MARLOW_MAX_ATTEMPTS) {
            marlowChallengePending = false;
            player.setMarlowHandshake(MarlowHandshake.FAILED);
            return;
        }

        marlowAttempts++;
        marlowChallengeId = ThreadLocalRandom.current().nextInt();
        marlowChallengeSentAt = System.currentTimeMillis();
        byte[] payload = new byte[]{
                (byte) (marlowChallengeId >>> 24),
                (byte) (marlowChallengeId >>> 16),
                (byte) (marlowChallengeId >>> 8),
                (byte) marlowChallengeId
        };
        player.setMarlowHandshake(MarlowHandshake.CHALLENGED);
        player.getUser().sendPacket(new WrapperPlayServerPluginMessage(MARLOW_CHALLENGE_CHANNEL, payload));
    }

    private void handleBrand(byte[] data) {
        String brand = "Vanilla";

        if (data.length > 64 || data.length == 0) {
            player.setClientBrand(brand);
            hasBrand = true;
            return;
        }

        byte[] minusLength = new byte[data.length - 1];
        System.arraycopy(data, 1, minusLength, 0, minusLength.length);

        brand = new String(minusLength).replace(" (Velocity)", "");
        brand = ChatUtil.stripColor(brand);

        player.setClientBrand(brand);
        hasBrand = true;
    }
}
