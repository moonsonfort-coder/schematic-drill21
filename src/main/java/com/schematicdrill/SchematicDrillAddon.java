package com.schematicdrill;

import com.schematicdrill.modules.SchematicDrill;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SchematicDrillAddon extends MeteorAddon {
    public static final Logger LOG = LoggerFactory.getLogger("SchematicDrill");
    public static final Category CATEGORY = new Category("Drill");

    @Override
    public void onInitialize() {
        LOG.info("Schematic Drill loaded");
        Modules.get().add(new SchematicDrill());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.schematicdrill";
    }
}
