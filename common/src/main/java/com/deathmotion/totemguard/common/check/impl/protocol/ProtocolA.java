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

package com.deathmotion.totemguard.common.check.impl.protocol;

import com.deathmotion.totemguard.api.check.CheckType;
import com.deathmotion.totemguard.common.check.CheckImpl;
import com.deathmotion.totemguard.common.check.annotations.CheckData;
import com.deathmotion.totemguard.common.check.annotations.RequiresTickEnd;
import com.deathmotion.totemguard.common.check.type.PacketCheck;
import com.deathmotion.totemguard.common.player.TGPlayer;
import com.deathmotion.totemguard.common.player.inventory.InventoryConstants;
import com.deathmotion.totemguard.common.player.inventory.PacketInventory;
import com.deathmotion.totemguard.common.player.data.TeleportData;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;

import java.util.ArrayList;
import java.util.List;

@RequiresTickEnd
@CheckData(description = "Slot change after action in same tick", type = CheckType.PROTOCOL)
public class ProtocolA extends CheckImpl implements PacketCheck {

    private static final int MAX_RECORDED_ACTIONS = 4;
    private static final int MAX_ITEM_NAME = 12;

    private final PacketInventory inventory;
    private final List<String> tickActions = new ArrayList<>();
    private String lastFlushingAction;
    private long lastActionAt;
    private int slotBeforeChange = -1;

    public ProtocolA(TGPlayer player) {
        super(player);
        this.inventory = player.getInventory();
    }

    private static String flushingActionName(PacketTypeCommon type, PacketReceiveEvent event) {
        if (type == PacketType.Play.Client.ATTACK) return "attack";
        if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            return new WrapperPlayClientInteractEntity(event).getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK
                    ? "attack"
                    : "interact";
        }
        if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) return "place";
        return null;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        final PacketTypeCommon type = event.getPacketType();

        if (type == PacketType.Play.Client.CLIENT_TICK_END) {
            lastFlushingAction = null;
            tickActions.clear();
            return;
        }

        if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            int from = slotBeforeChange;
            int to = new WrapperPlayClientHeldItemChange(event).getSlot();
            slotBeforeChange = to;
            recordAction("slot" + to);
            if (lastFlushingAction == null) return;
            TeleportData teleportData = player.getData().getTeleportData();
            if (teleportData.lastTickHadTeleport() || teleportData.hasPendingTeleport()) return;

            // The inventory processor already applied the change, so the items of both slots are readable here.
            // DebugTemplate keeps at most 8 args and 64 bytes of args, so everything is kept short.
            fail("{0},slot={1}>{2},items={3}>{4},afterAction={5}ms,tick=[{6}]",
                    lastFlushingAction, from, to, hotbarItemName(from), hotbarItemName(to),
                    System.currentTimeMillis() - lastActionAt, String.join(",", tickActions));
            return;
        }

        String action = flushingActionName(type, event);
        if (action != null) {
            lastFlushingAction = action;
            lastActionAt = System.currentTimeMillis();
            recordAction(action);
        }
        slotBeforeChange = inventory.getSelectedHotbarIndex();
    }

    private void recordAction(String action) {
        if (tickActions.size() < MAX_RECORDED_ACTIONS) tickActions.add(action);
    }

    private String hotbarItemName(int hotbarIndex) {
        if (hotbarIndex < 0 || hotbarIndex > 8) return "?";
        ItemStack item = inventory.getItem(InventoryConstants.HOTBAR_START + hotbarIndex);
        if (item == null || item.isEmpty()) return "empty";
        String name = item.getType().getName().getKey();
        return name.length() <= MAX_ITEM_NAME ? name : name.substring(0, MAX_ITEM_NAME);
    }
}
