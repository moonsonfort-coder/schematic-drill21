# Schematic Drill (Meteor Client addon, Minecraft 1.21.11)

One mod, one jar. Adds a "Schematic Drill" module (category "Drill") that mines an
area (or a .schem / .litematic footprint) with a 3x3 drill, shows a time estimate,
and has a dashboard GUI (module settings -> "Open dashboard").

## Build (JDK 21 required)

    # No gradlew in this folder yet? Generate it once (needs Gradle 8.14+ installed):
    gradle wrapper --gradle-version 8.14.3
    ./gradlew build        # Windows: gradlew.bat build

Output: build/libs/schematic-drill-1.0.0.jar

## Install
Fabric Loader + Meteor Client for 1.21.11. Put BOTH jars in .minecraft/mods.

## Use
1. Meteor GUI -> Drill -> Schematic Drill -> "Open dashboard".
2. Stand at the first corner -> "Pos1 = my position".
3. Type a schematic filename in the module settings -> "Load schematic" (or set pos2 manually).
4. Hold a pickaxe in the hotbar -> Start.
