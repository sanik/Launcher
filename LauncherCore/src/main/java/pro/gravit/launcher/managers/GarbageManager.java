package pro.gravit.launcher.managers;

import java.util.HashSet;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import pro.gravit.launcher.NeedGarbageCollection;

public class GarbageManager {
    static class Entry {
        NeedGarbageCollection invoke;
        long timer;

        public Entry(NeedGarbageCollection invoke, long timer) {
            this.invoke = invoke;
            this.timer = timer;
        }
    }

    private static final Timer timer = new Timer("GarbageTimer");

    private static final Set<Entry> NEED_GARBARE_COLLECTION = new HashSet<>();

    public static void gc() {
        for (Entry gc : NEED_GARBARE_COLLECTION)
            gc.invoke.garbageCollection();
    }

    public static void registerNeedGC(NeedGarbageCollection gc) {
        NEED_GARBARE_COLLECTION.add(new Entry(gc, 0L));
    }

    public static void registerNeedGC(NeedGarbageCollection gc, long time) {
        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                gc.garbageCollection();
            }
        };
        timer.schedule(task, time);
        NEED_GARBARE_COLLECTION.add(new Entry(gc, time));
    }

    public static void unregisterNeedGC(NeedGarbageCollection gc) {
        NEED_GARBARE_COLLECTION.removeIf(e -> e.invoke == gc);
    }
}
