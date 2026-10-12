package com.schematicdrill.util;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;

import java.io.IOException;
import java.nio.file.Path;

/** Reads the bounding-box size (x, y, z) from a .schem (Sponge) or .litematic file. */
public class SchemSize {
    public static int[] read(Path file) throws IOException {
        NbtCompound root = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());

        // Litematica
        if (root.contains("Metadata")) {
            NbtCompound s = root.getCompoundOrEmpty("Metadata").getCompoundOrEmpty("EnclosingSize");
            int x = Math.abs(s.getInt("x", 0)), y = Math.abs(s.getInt("y", 0)), z = Math.abs(s.getInt("z", 0));
            if (x == 0 || y == 0 || z == 0) throw new IOException("Litematic has no size data");
            return new int[]{x, y, z};
        }

        // Sponge v3 wraps everything in "Schematic"
        if (root.contains("Schematic")) root = root.getCompoundOrEmpty("Schematic");

        int w = root.getShort("Width", (short) 0) & 0xFFFF;
        int h = root.getShort("Height", (short) 0) & 0xFFFF;
        int l = root.getShort("Length", (short) 0) & 0xFFFF;
        if (w == 0 || h == 0 || l == 0) throw new IOException("Unsupported schematic format");
        return new int[]{w, h, l};
    }
}
