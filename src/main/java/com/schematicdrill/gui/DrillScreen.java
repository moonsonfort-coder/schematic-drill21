package com.schematicdrill.gui;

import com.schematicdrill.modules.SchematicDrill;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;

public class DrillScreen extends WindowScreen {
    private final SchematicDrill drill = Modules.get().get(SchematicDrill.class);

    private WLabel area, blocks, tunnel, speed, elapsed, eta, status, message, pct;
    private WBar bar;
    private WButton toggle;

    public DrillScreen(GuiTheme theme) {
        super(theme, "Schematic Drill  3x3");
    }

    @Override
    public void initWidgets() {
        message = add(theme.label("")).expandX().widget();

        // progress
        pct = add(theme.label("0%")).centerX().widget();
        bar = add(new WBar()).expandX().widget();
        add(theme.horizontalSeparator()).expandX();

        // stats
        WTable t = add(theme.table()).expandX().widget();
        area = row(t, "Area");
        blocks = row(t, "Blocks mined");
        tunnel = row(t, "Tunnel");
        speed = row(t, "Speed");
        elapsed = row(t, "Elapsed");
        eta = row(t, "Time remaining");
        status = row(t, "Status");

        add(theme.horizontalSeparator()).expandX();

        // buttons
        WHorizontalList l1 = add(theme.horizontalList()).expandX().widget();
        toggle = l1.add(theme.button("Start")).expandX().widget();
        toggle.action = () -> { drill.toggle(); update(); };
        WButton recalc = l1.add(theme.button("Recalculate")).expandX().widget();
        recalc.action = () -> { if (!drill.isActive()) { drill.plan(); message.set(""); } update(); };

        WHorizontalList l2 = add(theme.horizontalList()).expandX().widget();
        WButton here = l2.add(theme.button("Pos1 = my position")).expandX().widget();
        here.action = () -> { drill.setPos1ToPlayer(); message.set("Pos1 set to your position"); update(); };
        WButton load = l2.add(theme.button("Load schematic")).expandX().widget();
        load.action = () -> { message.set(drill.loadSchematic()); drill.plan(); update(); };

        update();
    }

    private WLabel row(WTable t, String name) {
        t.add(theme.label(name)).expandCellX();
        WLabel v = t.add(theme.label("-")).right().widget();
        t.row();
        return v;
    }

    @Override
    public void tick() {
        super.tick();
        update();
    }

    private void update() {
        if (area == null) return;
        area.set(drill.areaText());
        blocks.set(drill.planned() ? drill.mined() + " / " + drill.totalBlocks() : "-");
        tunnel.set(drill.tunnelText());
        speed.set(drill.speedText());
        elapsed.set(drill.elapsedText());
        eta.set(drill.etaText());
        status.set(drill.status());
        toggle.set(drill.isActive() ? "Stop" : "Start");
        double p = drill.progress();
        bar.pct = p;
        pct.set(String.format("%.1f%%", p * 100));
    }

    /** Simple progress bar. */
    private static class WBar extends WWidget {
        double pct;

        @Override
        protected void onCalculateSize() {
            width = 280;
            height = 14;
        }

        @Override
        protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
            renderer.quad(x, y, width, height, new Color(35, 35, 40, 230));
            renderer.quad(x, y, width * pct, height, new Color(90, 200, 120, 255));
        }
    }
}
