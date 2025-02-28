/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
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
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.translator.protocol.java;

import com.github.steveice10.mc.protocol.packet.login.clientbound.ClientboundLoginDisconnectPacket;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import org.geysermc.common.PlatformType;
import org.geysermc.geyser.network.MinecraftProtocol;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.text.GeyserLocale;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.geyser.translator.protocol.Translator;
import org.geysermc.geyser.translator.text.MessageTranslator;

import java.util.List;

@Translator(packet = ClientboundLoginDisconnectPacket.class)
public class JavaLoginDisconnectTranslator extends PacketTranslator<ClientboundLoginDisconnectPacket> {

    @Override
    public void translate(GeyserSession session, ClientboundLoginDisconnectPacket packet) {
        Component disconnectReason = packet.getReason();

        boolean isOutdatedMessage = false;
        if (disconnectReason instanceof TranslatableComponent component) {
            String key = component.key();
            isOutdatedMessage = "multiplayer.disconnect.incompatible".equals(key) ||
                    // Legacy string (starting from at least 1.15.2)
                    "multiplayer.disconnect.outdated_server".equals(key)
                    // Reproduced on 1.15.2 server with ViaVersion 4.0.0-21w20a with 1.18.2 Java client
                    || key.startsWith("Outdated server!");
        } else {
            if (disconnectReason instanceof TextComponent component) {
                if (component.content().startsWith("Outdated server!")) {
                    // Reproduced with vanilla 1.8.8 server and 1.18.2 Java client
                    isOutdatedMessage = true;
                } else {
                    List<Component> children = component.children();
                    for (int i = 0; i < children.size(); i++) {
                        if (children.get(i) instanceof TextComponent child && child.content().startsWith("Outdated server!")) {
                            // Reproduced on Paper 1.17.1
                            isOutdatedMessage = true;
                            break;
                        }
                    }
                }
            }
        }

        String serverDisconnectMessage = MessageTranslator.convertMessage(disconnectReason, session.getLocale());
        String disconnectMessage;
        if (isOutdatedMessage) {
            String locale = session.getLocale();
            PlatformType platform = session.getGeyser().getPlatformType();
            String outdatedType = (platform == PlatformType.BUNGEECORD || platform == PlatformType.VELOCITY) ?
                    "geyser.network.remote.outdated.proxy" : "geyser.network.remote.outdated.server";
            disconnectMessage = GeyserLocale.getPlayerLocaleString(outdatedType, locale, MinecraftProtocol.getJavaVersions().get(0)) + '\n'
                    + GeyserLocale.getPlayerLocaleString("geyser.network.remote.original_disconnect_message", locale, serverDisconnectMessage);
        } else {
            disconnectMessage = serverDisconnectMessage;
        }

        // The client doesn't manually get disconnected so we have to do it ourselves
        session.disconnect(disconnectMessage);
    }

    @Override
    public boolean shouldExecuteInEventLoop() {
        return false;
    }
}
