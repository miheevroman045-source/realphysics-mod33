package com.example.homelander;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public final class HomelanderClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        final KeyBinding laser = bind("laser", GLFW.GLFW_KEY_H);
        final KeyBinding flight = bind("flight", GLFW.GLFW_KEY_V);
        final KeyBinding wave = bind("shockwave", GLFW.GLFW_KEY_G);
        final KeyBinding dash = bind("dash", GLFW.GLFW_KEY_R);
        final KeyBinding punch = bind("punch", GLFW.GLFW_KEY_B);
        final KeyBinding land = bind("landing", GLFW.GLFW_KEY_N);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.getNetworkHandler() == null) return;
            while (laser.wasPressed()) send((byte) 0);
            while (flight.wasPressed()) send((byte) 1);
            while (wave.wasPressed()) send((byte) 2);
            while (dash.wasPressed()) send((byte) 3);
            while (punch.wasPressed()) send((byte) 4);
            while (land.wasPressed()) send((byte) 5);
        });
    }

    private static KeyBinding bind(String action, int key) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.homelander." + action, InputUtil.Type.KEYSYM, key,
                "category.homelander.powers"));
    }

    private static void send(byte action) {
        ClientPlayNetworking.send(new AbilityPayload(action));
    }
}
