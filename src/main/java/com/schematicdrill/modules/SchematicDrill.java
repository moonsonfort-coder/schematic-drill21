package com.schematicdrill.modules;

import com.schematicdrill.SchematicDrillAddon;
import com.schematicdrill.gui.DrillScreen;
import com.schematicdrill.util.SchemSize;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.nio.file.Path;
import java.util.*;

public class SchematicDrill extends Module {
    public enum Axis { X, Z }

    private record Tunnel(int ulo, int uhi, int ylo, int yhi, boolean forward) {}

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<String> schematic = sg.add(new StringSetting.Builder()
        .name("schematic").description("File in .minecraft/schematics (.schem / .litematic). Sets pos2 from its size, using pos1 as origin.")
        .defaultValue("").build());
    private final Setting<BlockPos> pos1 = sg.add(new BlockPosSetting.Builder()
        .name("pos1").description("First corner (or schematic origin).").defaultValue(BlockPos.ORIGIN).build());
    private final Setting<BlockPos> pos2 = sg.add(new BlockPosSetting.Builder()
        .name("pos2").description("Opposite corner.").defaultValue(BlockPos.ORIGIN).build());
    private final Setting<Axis> axis = sg.add(new EnumSetting.Builder<Axis>()
        .name("tunnel-axis").description("Direction the tunnels run.").defaultValue(Axis.Z).build());
    private final Setting<Integer> minHealth = sg.add(new IntSetting.Builder()
        .name("stop-health").description("Stops when health drops below this.").defaultValue(8).range(0, 20).sliderMax(20).build());

    // plan
    private final List<Tunnel> tunnels = new ArrayList<>();
    private final Map<BlockState, Double> tickCache = new HashMap<>();
    private int x0, x1, y0, y1, z0, z1, minW, maxW, lenW;
    private boolean planned;

    // stats
    private long totalBlocks, mined;
    private double totalEst, estDone;
    private long elapsed;
    private String status = "Idle";

    // run state
    private boolean running;
    private int tIdx, wStep, moveTicks;
    private List<BlockPos> pending;
    private double sliceEst;
    private int sliceCount;
    private boolean accounted;

    public SchematicDrill() {
        super(SchematicDrillAddon.CATEGORY, "schematic-drill", "Mines an area with a 3x3 drill, with time estimate.");
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WButton b = theme.button("Open dashboard");
        b.action = () -> mc.setScreen(new DrillScreen(theme));
        return b;
    }

    // ---------- public API for the GUI ----------

    public void setPos1ToPlayer() {
        if (mc.player != null) pos1.set(mc.player.getBlockPos());
    }

    public String loadSchematic() {
        try {
            Path f = MinecraftClient.getInstance().runDirectory.toPath().resolve("schematics").resolve(schematic.get());
            int[] s = SchemSize.read(f);
            BlockPos a = pos1.get();
            pos2.set(a.add(s[0] - 1, s[1] - 1, s[2] - 1));
            return "Loaded " + s[0] + "x" + s[1] + "x" + s[2];
        } catch (Exception e) {
            return "Could not read schematic: " + e.getMessage();
        }
    }

    public String areaText() {
        BlockPos a = pos1.get(), b = pos2.get();
        return (Math.abs(a.getX() - b.getX()) + 1) + " x " + (Math.abs(a.getY() - b.getY()) + 1) + " x " + (Math.abs(a.getZ() - b.getZ()) + 1);
    }

    public double progress() { return totalEst <= 0 ? 0 : Math.min(1, estDone / totalEst); }
    public long mined() { return mined; }
    public long totalBlocks() { return totalBlocks; }
    public String status() { return status; }
    public boolean planned() { return planned; }
    public String tunnelText() { return planned ? Math.min(tIdx + 1, tunnels.size()) + " / " + tunnels.size() : "-"; }
    public String elapsedText() { return fmt(elapsed / 20.0); }
    public String etaText() {
        if (!planned) return "-";
        double remaining = Math.max(0, totalEst - estDone);
        double ratio = (running && estDone > 200 && elapsed > 100) ? elapsed / estDone : 1.15; // measured vs. predicted
        return fmt(remaining * ratio / 20.0);
    }
    public String speedText() {
        if (elapsed < 40) return "-";
        return String.format("%.1f blocks/s", mined / (elapsed / 20.0));
    }

    /** Builds the tunnel plan and the time estimate. Safe to call while disabled. */
    public boolean plan() {
        planned = false;
        if (mc.world == null || mc.player == null) { status = "Join a world first"; return false; }

        BlockPos a = pos1.get(), b = pos2.get();
        x0 = Math.min(a.getX(), b.getX()); x1 = Math.max(a.getX(), b.getX());
        y0 = Math.max(mc.world.getBottomY(), Math.min(a.getY(), b.getY()));
        y1 = Math.min(mc.world.getBottomY() + mc.world.getHeight() - 1, Math.max(a.getY(), b.getY()));
        z0 = Math.min(a.getZ(), b.getZ()); z1 = Math.max(a.getZ(), b.getZ());

        long volume = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        if (volume <= 1 || volume > 8_000_000L) { status = "Set a valid area (pos1 / pos2)"; return false; }

        int u0 = axis.get() == Axis.Z ? x0 : z0, u1 = axis.get() == Axis.Z ? x1 : z1;
        minW = axis.get() == Axis.Z ? z0 : x0; maxW = axis.get() == Axis.Z ? z1 : x1;
        lenW = maxW - minW + 1;

        int lanes = (u1 - u0 + 3) / 3, layers = (y1 - y0 + 3) / 3;
        tunnels.clear();
        boolean fwd = true;
        for (int j = layers - 1, k = 0; j >= 0; j--, k++) { // top layer first, snake pattern
            int ylo = y0 + 3 * j, yhi = Math.min(ylo + 2, y1);
            for (int n = 0; n < lanes; n++) {
                int i = (k % 2 == 0) ? n : lanes - 1 - n;
                int ulo = u0 + 3 * i;
                tunnels.add(new Tunnel(ulo, Math.min(ulo + 2, u1), ylo, yhi, fwd));
                fwd = !fwd;
            }
        }

        // estimate
        tickCache.clear();
        totalBlocks = 0;
        double blockTicks = 0, known = 0, knownCount = 0;
        long unloaded = 0;
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean loaded = mc.world.getChunkManager().isChunkLoaded(x >> 4, z >> 4);
                if (!loaded) { unloaded += (y1 - y0 + 1); continue; }
                for (int y = y0; y <= y1; y++) {
                    p.set(x, y, z);
                    BlockState s = mc.world.getBlockState(p);
                    if (isDone(s, p)) continue;
                    double t = ticksFor(s, p);
                    totalBlocks++; blockTicks += t; known += t; knownCount++;
                }
            }
        }
        double avg = knownCount > 0 ? known / knownCount : 8;
        blockTicks += unloaded * avg * 0.9; // unloaded chunks: assume mostly solid
        totalBlocks += (long) (unloaded * 0.9);

        totalEst = blockTicks + (double) tunnels.size() * lenW * 5; // ~5 ticks of walking per slice
        estDone = 0; mined = 0; elapsed = 0; tIdx = 0; wStep = 0; pending = null; accounted = false;
        planned = true;
        status = "Ready";
        return true;
    }

    // ---------- run ----------

    @Override
    public void onActivate() {
        if (!plan()) { error(status); toggle(); return; }
        running = true;
        status = "Running";
        info("Estimated time: " + etaText() + " for ~" + totalBlocks + " blocks. Stand near the first tunnel start.");
    }

    @Override
    public void onDeactivate() {
        running = false;
        if (mc.options != null) mc.options.forwardKey.setPressed(false);
        if (status.equals("Running")) status = "Stopped";
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!running || mc.player == null || mc.world == null) return;

        if (mc.player.getHealth() < minHealth.get()) { finish("Stopped: low health"); return; }
        if (tIdx >= tunnels.size()) { finish("Done!"); return; }
        elapsed++;

        Tunnel t = tunnels.get(tIdx);
        int w = t.forward() ? minW + wStep : maxW - wStep;

        if (pending == null) {
            pending = new ArrayList<>();
            sliceEst = 5; sliceCount = 0; accounted = false; moveTicks = 0;
            for (int u = t.ulo(); u <= t.uhi(); u++)
                for (int y = t.ylo(); y <= t.yhi(); y++) {
                    BlockPos p = pos(u, y, w);
                    BlockState s = mc.world.getBlockState(p);
                    if (isDone(s, p)) continue;
                    pending.add(p); sliceCount++; sliceEst += ticksFor(s, p);
                }
        }

        // drop finished cells (also handles falling gravel/sand)
        pending.removeIf(p -> isDone(mc.world.getBlockState(p), p));
        Vec3d eye = mc.player.getEyePos();
        pending.sort(Comparator.comparingDouble(p -> eye.squaredDistanceTo(Vec3d.ofCenter(p))));

        if (!pending.isEmpty()) {
            BlockPos p = pending.get(0);
            if (eye.distanceTo(Vec3d.ofCenter(p)) > 4.4) { walkTo(Vec3d.ofCenter(p)); return; }
            mc.options.forwardKey.setPressed(false);
            BlockState s = mc.world.getBlockState(p);
            Rotations.rotate(Rotations.getYaw(p), Rotations.getPitch(p), () -> {
                FindItemResult tool = InvUtils.findFastestTool(s);
                if (tool.found() && tool.isHotbar()) InvUtils.swap(tool.slot(), false);
                BlockUtils.breakBlock(p, true);
            });
            return;
        }

        if (!accounted) { mined += sliceCount; estDone += sliceEst; accounted = true; }

        // walk into the cleared slice
        Vec3d target = new Vec3d(
            axis.get() == Axis.Z ? (t.ulo() + t.uhi()) / 2.0 + 0.5 : w + 0.5,
            t.ylo(),
            axis.get() == Axis.Z ? w + 0.5 : (t.ulo() + t.uhi()) / 2.0 + 0.5);

        double dx = target.x - mc.player.getX(), dz = target.z - mc.player.getZ();
        if (Math.sqrt(dx * dx + dz * dz) < 0.3 && Math.abs(mc.player.getY() - t.ylo()) < 0.6) {
            mc.options.forwardKey.setPressed(false);
            pending = null;
            if (++wStep >= lenW) { wStep = 0; tIdx++; }
        } else {
            if (++moveTicks > 80) { finish("Stopped: got stuck (check for obstacles)"); return; }
            walkTo(target);
        }
    }

    private void walkTo(Vec3d tgt) {
        double dx = tgt.x - mc.player.getX(), dz = tgt.z - mc.player.getZ();
        Rotations.rotate(Math.toDegrees(Math.atan2(dz, dx)) - 90, 0);
        mc.options.forwardKey.setPressed(true);
    }

    private void finish(String msg) {
        status = msg;
        info(msg);
        toggle();
    }

    // ---------- helpers ----------

    private BlockPos pos(int u, int y, int w) {
        return axis.get() == Axis.Z ? new BlockPos(u, y, w) : new BlockPos(w, y, u);
    }

    private boolean isDone(BlockState s, BlockPos p) {
        return s.isAir() || !s.getFluidState().isEmpty() || s.getHardness(mc.world, p) < 0;
    }

    /** Predicted ticks to break a block with the best tool in the hotbar (+1 tick of overhead). */
    private double ticksFor(BlockState s, BlockPos p) {
        return tickCache.computeIfAbsent(s, st -> {
            float hardness = st.getHardness(mc.world, p);
            if (hardness <= 0) return 1.0;
            double best = 0;
            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.player.getInventory().getStack(i);
                float speed = stack.getMiningSpeedMultiplier(st);
                boolean ok = !st.isToolRequired() || stack.isSuitableFor(st);
                best = Math.max(best, speed / hardness / (ok ? 30.0 : 100.0));
            }
            if (best <= 0) best = 1.0 / hardness / 100.0;
            return Math.ceil(1.0 / Math.min(best, 1.0)) + 1;
        });
    }

    private static String fmt(double seconds) {
        long s = Math.max(0, Math.round(seconds));
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format("%dh %02dm %02ds", h, m, sec);
        if (m > 0) return String.format("%dm %02ds", m, sec);
        return sec + "s";
    }
}
