// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame;

import java.nio.file.Files;
import java.nio.file.Path;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Runs the Android Kotlin entry points against a desktop build of their JNI library. */
public class StationInspectorDesktop {
    private static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
    public static void main(String[] args) throws Exception {
        long handle = Engine.open(Files.readAllBytes(Path.of(args[0])));
        if (handle == 0) throw new AssertionError("Save unreadable");
        try {
            JsonObject world = object(Engine.geometry(handle));
            double clock = world.get("clock").getAsDouble();
            for (var row : world.getAsJsonArray("stations")) {
                JsonArray marker = row.getAsJsonArray();
                if (!marker.get(2).getAsString().equals("Rector St")) continue;
                int node = marker.get(3).getAsInt();
                JsonObject station = object(StationInspector.read(handle,node,-1,0,clock));
                if (station.getAsJsonArray("platforms").size() != 3) continue;
                var departure = station.getAsJsonArray("departures").get(0).getAsJsonObject();
                var route = object(StationInspector.read(handle,-1,departure.get("line").getAsInt(),
                        departure.get("run").getAsInt(),clock));
                JsonArray stops = route.getAsJsonArray("stops");
                boolean connects = false;
                for (var stop : stops) {
                    var item = stop.getAsJsonObject();
                    if (item.get("node").getAsInt() == departure.get("node").getAsInt()) connects = true;
                }
                if (!connects || route.get("base").isJsonNull()) throw new AssertionError("Missing connection or timetable");
                System.out.printf("%s: %d platforms, %d lines, next %s, %d timed stops%n",
                        station.get("name").getAsString(),station.getAsJsonArray("platforms").size(),
                        station.getAsJsonArray("lines").size(),route.get("name").getAsString(),stops.size());
                return;
            }
            throw new AssertionError("Rector St missing");
        } finally { Engine.close(handle); }
    }
}
