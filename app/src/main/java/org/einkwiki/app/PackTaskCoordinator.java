package org.einkwiki.app;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-wide serial queue for catalog, verification and archive-file mutations. */
final class PackTaskCoordinator {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "einkwiki-packs");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private PackTaskCoordinator() {
    }

    static void execute(Runnable task) {
        EXECUTOR.execute(task);
    }
}
