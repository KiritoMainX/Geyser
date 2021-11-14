/*
 * Copyright (c) 2019-2021 GeyserMC. http://geysermc.org
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

package org.geysermc.connector.network.translators.java.level;

import com.github.steveice10.mc.protocol.data.game.chunk.BitStorage;
import com.github.steveice10.mc.protocol.data.game.chunk.ChunkSection;
import com.github.steveice10.mc.protocol.data.game.chunk.DataPalette;
import com.github.steveice10.mc.protocol.data.game.chunk.palette.GlobalPalette;
import com.github.steveice10.mc.protocol.data.game.chunk.palette.Palette;
import com.github.steveice10.mc.protocol.data.game.level.block.BlockEntityInfo;
import com.github.steveice10.mc.protocol.data.game.level.block.BlockEntityType;
import com.github.steveice10.mc.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import com.github.steveice10.opennbt.tag.builtin.CompoundTag;
import com.github.steveice10.packetlib.io.NetInput;
import com.github.steveice10.packetlib.io.stream.StreamNetInput;
import com.nukkitx.math.vector.Vector3i;
import com.nukkitx.nbt.NBTOutputStream;
import com.nukkitx.nbt.NbtMap;
import com.nukkitx.nbt.NbtUtils;
import com.nukkitx.network.VarInts;
import com.nukkitx.protocol.bedrock.packet.LevelChunkPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufOutputStream;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.geysermc.connector.network.session.GeyserSession;
import org.geysermc.connector.network.translators.PacketTranslator;
import org.geysermc.connector.network.translators.Translator;
import org.geysermc.connector.network.translators.world.BiomeTranslator;
import org.geysermc.connector.network.translators.world.block.BlockStateValues;
import org.geysermc.connector.network.translators.world.block.entity.BedrockOnlyBlockEntity;
import org.geysermc.connector.network.translators.world.block.entity.BlockEntityTranslator;
import org.geysermc.connector.network.translators.world.block.entity.SkullBlockEntityTranslator;
import org.geysermc.connector.network.translators.world.chunk.BlockStorage;
import org.geysermc.connector.network.translators.world.chunk.GeyserChunkSection;
import org.geysermc.connector.network.translators.world.chunk.bitarray.BitArray;
import org.geysermc.connector.network.translators.world.chunk.bitarray.BitArrayVersion;
import org.geysermc.connector.registry.BlockRegistries;
import org.geysermc.connector.utils.BlockEntityUtils;
import org.geysermc.connector.utils.ChunkUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import static org.geysermc.connector.utils.ChunkUtils.*;

@Translator(packet = ClientboundLevelChunkWithLightPacket.class)
public class JavaLevelChunkWithLightTranslator extends PacketTranslator<ClientboundLevelChunkWithLightPacket> {

    @Override
    public void translate(GeyserSession session, ClientboundLevelChunkWithLightPacket packet) {
        if (session.isSpawned()) {
            ChunkUtils.updateChunkPosition(session, session.getPlayerEntity().getPosition().toInt());
        }

        // Ensure that, if the player is using lower world heights, the position is not offset
        int yOffset = session.getChunkCache().getChunkMinY();

        // Temporarily stores compound tags of Bedrock-only block entities
        List<NbtMap> bedrockOnlyBlockEntities = new ArrayList<>();
        DataPalette[] javaChunks = new DataPalette[session.getChunkCache().getChunkHeightY()];
        DataPalette[] javaBiomes = new DataPalette[session.getChunkCache().getChunkHeightY()];

        BitSet waterloggedPaletteIds = new BitSet();
        BitSet pistonOrFlowerPaletteIds = new BitSet();

        boolean overworld = session.getChunkCache().isExtendedHeight();
        int maxBedrockSectionY = ((overworld ? MAXIMUM_ACCEPTED_HEIGHT_OVERWORLD : MAXIMUM_ACCEPTED_HEIGHT) >> 4) - 1;

        int sectionCount;
        byte[] payload;
        ByteBuf byteBuf = null;
        GeyserChunkSection[] sections = new GeyserChunkSection[javaChunks.length - yOffset];

        try {
            NetInput in = new StreamNetInput(new ByteArrayInputStream(packet.getChunkData()));
            for (int sectionY = 0; sectionY < session.getChunkCache().getChunkHeightY(); sectionY++) {
                int bedrockSectionY = sectionY + (yOffset - ((overworld ? MINIMUM_ACCEPTED_HEIGHT_OVERWORLD : MINIMUM_ACCEPTED_HEIGHT) >> 4));
                if (bedrockSectionY < 0 || maxBedrockSectionY < bedrockSectionY) {
                    // Ignore this chunk section since it goes outside the bounds accepted by the Bedrock client
                    continue;
                }

                ChunkSection javaSection = ChunkSection.read(in);
                javaChunks[sectionY] = javaSection.getChunkData();
                javaBiomes[sectionY] = javaSection.getBiomeData();

                // No need to encode an empty section...
                if (javaSection.isBlockCountEmpty()) {
                    continue;
                }

                Palette javaPalette = javaSection.getChunkData().getPalette();
                BitStorage javaData = javaSection.getChunkData().getStorage();

                if (javaPalette instanceof GlobalPalette) {
                    // As this is the global palette, simply iterate through the whole chunk section once
                    GeyserChunkSection section = new GeyserChunkSection(session.getBlockMappings().getBedrockAirId());
                    for (int yzx = 0; yzx < BlockStorage.SIZE; yzx++) {
                        int javaId = javaData.get(yzx);
                        int bedrockId = session.getBlockMappings().getBedrockBlockId(javaId);
                        int xzy = indexYZXtoXZY(yzx);
                        section.getBlockStorageArray()[0].setFullBlock(xzy, bedrockId);

                        if (BlockRegistries.WATERLOGGED.get().contains(javaId)) {
                            section.getBlockStorageArray()[1].setFullBlock(xzy, session.getBlockMappings().getBedrockWaterId());
                        }

                        // Check if block is piston or flower to see if we'll need to create additional block entities, as they're only block entities in Bedrock
                        if (BlockStateValues.getFlowerPotValues().containsKey(javaId) || BlockStateValues.getPistonValues().containsKey(javaId)) {
                            bedrockOnlyBlockEntities.add(BedrockOnlyBlockEntity.getTag(session,
                                    Vector3i.from((packet.getX() << 4) + (yzx & 0xF), ((sectionY + yOffset) << 4) + ((yzx >> 8) & 0xF), (packet.getZ() << 4) + ((yzx >> 4) & 0xF)),
                                    javaId
                            ));
                        }
                    }
                    sections[bedrockSectionY] = section;
                    continue;
                }

                IntList bedrockPalette = new IntArrayList(javaPalette.size());
                waterloggedPaletteIds.clear();
                pistonOrFlowerPaletteIds.clear();

                // Iterate through palette and convert state IDs to Bedrock, doing some additional checks as we go
                for (int i = 0; i < javaPalette.size(); i++) {
                    int javaId = javaPalette.idToState(i);
                    bedrockPalette.add(session.getBlockMappings().getBedrockBlockId(javaId));

                    if (BlockRegistries.WATERLOGGED.get().contains(javaId)) {
                        waterloggedPaletteIds.set(i);
                    }

                    // Check if block is piston or flower to see if we'll need to create additional block entities, as they're only block entities in Bedrock
                    if (BlockStateValues.getFlowerPotValues().containsKey(javaId) || BlockStateValues.getPistonValues().containsKey(javaId)) {
                        pistonOrFlowerPaletteIds.set(i);
                    }
                }

                // Add Bedrock-exclusive block entities
                // We only if the palette contained any blocks that are Bedrock-exclusive block entities to avoid iterating through the whole block data
                // for no reason, as most sections will not contain any pistons or flower pots
                if (!pistonOrFlowerPaletteIds.isEmpty()) {
                    for (int yzx = 0; yzx < BlockStorage.SIZE; yzx++) {
                        int paletteId = javaData.get(yzx);
                        if (pistonOrFlowerPaletteIds.get(paletteId)) {
                            bedrockOnlyBlockEntities.add(BedrockOnlyBlockEntity.getTag(session,
                                    Vector3i.from((packet.getX() << 4) + (yzx & 0xF), ((sectionY + yOffset) << 4) + ((yzx >> 8) & 0xF), (packet.getZ() << 4) + ((yzx >> 4) & 0xF)),
                                    javaPalette.idToState(paletteId)
                            ));
                        }
                    }
                }

                BitArray bedrockData = BitArrayVersion.forBitsCeil(javaData.getBitsPerEntry()).createArray(BlockStorage.SIZE);
                BlockStorage layer0 = new BlockStorage(bedrockData, bedrockPalette);
                BlockStorage[] layers;

                // Convert data array from YZX to XZY coordinate order
                if (waterloggedPaletteIds.isEmpty()) {
                    // No blocks are waterlogged, simply convert coordinate order
                    // This could probably be optimized further...
                    for (int yzx = 0; yzx < BlockStorage.SIZE; yzx++) {
                        bedrockData.set(indexYZXtoXZY(yzx), javaData.get(yzx));
                    }

                    layers = new BlockStorage[]{ layer0 };
                } else {
                    // The section contains waterlogged blocks, we need to convert coordinate order AND generate a V1 block storage for
                    // layer 1 with palette ID 1 indicating water
                    int[] layer1Data = new int[BlockStorage.SIZE >> 5];
                    for (int yzx = 0; yzx < BlockStorage.SIZE; yzx++) {
                        int paletteId = javaData.get(yzx);
                        int xzy = indexYZXtoXZY(yzx);
                        bedrockData.set(xzy, paletteId);

                        if (waterloggedPaletteIds.get(paletteId)) {
                            layer1Data[xzy >> 5] |= 1 << (xzy & 0x1F);
                        }
                    }

                    // V1 palette
                    IntList layer1Palette = new IntArrayList(2);
                    layer1Palette.add(session.getBlockMappings().getBedrockAirId()); // Air - see BlockStorage's constructor for more information
                    layer1Palette.add(session.getBlockMappings().getBedrockWaterId());

                    layers = new BlockStorage[]{ layer0, new BlockStorage(BitArrayVersion.V1.createArray(BlockStorage.SIZE, layer1Data), layer1Palette) };
                }

                sections[bedrockSectionY] = new GeyserChunkSection(layers);
            }

            session.getChunkCache().addToCache(packet.getX(), packet.getZ(), javaChunks);

            BlockEntityInfo[] blockEntities = packet.getBlockEntities();
            NbtMap[] bedrockBlockEntities = new NbtMap[blockEntities.length + bedrockOnlyBlockEntities.size()];
            int blockEntityCount = 0;
            while (blockEntityCount < blockEntities.length) {
                BlockEntityInfo blockEntity = blockEntities[blockEntityCount];
                CompoundTag tag = blockEntity.getNbt();
                BlockEntityType type = blockEntity.getType();
                int x = blockEntity.getX();
                int y = blockEntity.getY();
                int z = blockEntity.getZ();

                // Get the Java block state ID from block entity position
                DataPalette section = javaChunks[(y >> 4) - yOffset];
                int blockState = section.get(x & 0xF, y & 0xF, z & 0xF);

                if (type == BlockEntityType.LECTERN && BlockStateValues.getLecternBookStates().get(blockState)) {
                    // If getLecternBookStates is false, let's just treat it like a normal block entity
                    bedrockBlockEntities[blockEntityCount] = session.getConnector().getWorldManager().getLecternDataAt(
                            session, blockEntity.getX(), blockEntity.getY(), blockEntity.getZ(), true);
                    blockEntityCount++;
                    continue;
                }

                BlockEntityTranslator blockEntityTranslator = BlockEntityUtils.getBlockEntityTranslator(type);
                bedrockBlockEntities[blockEntityCount] = blockEntityTranslator.getBlockEntityTag(type, x, y, z, tag, blockState);

                // Check for custom skulls
                if (session.getPreferencesCache().showCustomSkulls() && tag != null && tag.contains("SkullOwner")) {
                    SkullBlockEntityTranslator.spawnPlayer(session, tag, blockState);
                }
                blockEntityCount++;
            }

            // Append Bedrock-exclusive block entities to output array
            for (NbtMap tag : bedrockOnlyBlockEntities) {
                bedrockBlockEntities[blockEntityCount] = tag;
                blockEntityCount++;
            }

            // Find highest section
            sectionCount = sections.length - 1;
            while (sectionCount >= 0 && sections[sectionCount] == null) {
                sectionCount--;
            }
            sectionCount++;

            // Estimate chunk size
            int size = 0;
            for (int i = 0; i < sectionCount; i++) {
                GeyserChunkSection section = sections[i];
                size += (section != null ? section : session.getBlockMappings().getEmptyChunkSection()).estimateNetworkSize();
            }
            size += ChunkUtils.EMPTY_CHUNK_DATA.length; // Consists only of biome data
            size += 1; // Border blocks
            size += 1; // Extra data length (always 0)
            size += bedrockBlockEntities.length * 64; // Conservative estimate of 64 bytes per tile entity

            // Allocate output buffer
            byteBuf = ByteBufAllocator.DEFAULT.buffer(size);
            for (int i = 0; i < sectionCount; i++) {
                GeyserChunkSection section = sections[i];
                (section != null ? section : session.getBlockMappings().getEmptyChunkSection()).writeToNetwork(byteBuf);
            }

            // At this point we're dealing with Bedrock chunk sections
            int dimensionOffset = (overworld ? MINIMUM_ACCEPTED_HEIGHT_OVERWORLD : MINIMUM_ACCEPTED_HEIGHT) >> 4;
            for (int i = 0; i < sectionCount; i++) {
                int biomeYOffset = dimensionOffset + i;
                if (biomeYOffset < yOffset) {
                    // Ignore this biome section since it goes below the height of the Java world
                    byteBuf.writeBytes(ChunkUtils.EMPTY_BIOME_DATA);
                    continue;
                }
                BiomeTranslator.toNewBedrockBiome(session, javaBiomes[i]).writeToNetwork(byteBuf);
            }

            // As of 1.17.10, Bedrock hardcodes to always read 32 biome sections
            int remainingEmptyBiomes = 32 - sectionCount;
            for (int i = 0; i < remainingEmptyBiomes; i++) {
                byteBuf.writeBytes(ChunkUtils.EMPTY_BIOME_DATA);
            }

            byteBuf.writeByte(0); // Border blocks - Edu edition only
            VarInts.writeUnsignedInt(byteBuf, 0); // extra data length, 0 for now

            // Encode tile entities into buffer
            NBTOutputStream nbtStream = NbtUtils.createNetworkWriter(new ByteBufOutputStream(byteBuf));
            for (NbtMap blockEntity : bedrockBlockEntities) {
                nbtStream.writeTag(blockEntity);
            }

            // Copy data into byte[], because the protocol lib really likes things that are s l o w
            byteBuf.readBytes(payload = new byte[byteBuf.readableBytes()]);
        } catch (IOException e) {
            session.getConnector().getLogger().error("IO error while encoding chunk", e);
            return;
        } finally {
            if (byteBuf != null) {
                byteBuf.release(); // Release buffer to allow buffer pooling to be useful
            }
        }

        LevelChunkPacket levelChunkPacket = new LevelChunkPacket();
        levelChunkPacket.setSubChunksLength(sectionCount);
        levelChunkPacket.setCachingEnabled(false);
        levelChunkPacket.setChunkX(packet.getX());
        levelChunkPacket.setChunkZ(packet.getZ());
        levelChunkPacket.setData(payload);
        session.sendUpstreamPacket(levelChunkPacket);
    }
}