package paulscode.android.mupen64plusae.jni;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.mainui.stateapi.MainUIStateApi;

/**
 * Mupen64Plus AE's side of the MainUI State API ({@link MainUIStateApi}), in the emulation
 * process. MainUI's slots 0-9 are the core's own (the in-game menu's), saved and loaded the way
 * the menu does, the selected slot put back after; MainUI's resume state is a file of its own,
 * MainUI/resume.st in the game's save folder. Each is answered once the core says it is done.
 */
final class MainUIStates implements MainUIStateApi.Emulator {
    private final CoreService service;

    MainUIStates(CoreService service) { this.service = service; }

    private CoreInterface core() { return service.mainuiCore(); }

    @Override
    public boolean isRunning() {
        CoreTypes.m64p_emu_state s = service.getState();
        return s == CoreTypes.m64p_emu_state.M64EMU_RUNNING || s == CoreTypes.m64p_emu_state.M64EMU_PAUSED;
    }

    @Override
    public boolean isReady() {
        return service.getState() == CoreTypes.m64p_emu_state.M64EMU_RUNNING && !service.isPaused() && !service.isShuttingDown();
    }

    @Override
    public int slotCount() { return service.getSlotQuantity(); }

    /** MainUI's pause took effect (not one the user or the in-game drawer holds). */
    private volatile boolean pausedByMainUi;

    @Override
    public boolean pause() {
        if (!isRunning()) return false;
        if (service.isPaused()) return true;
        service.pauseEmulator();
        pausedByMainUi = service.isPaused();
        return pausedByMainUi;
    }

    @Override
    public void resume() {
        if (!pausedByMainUi) return;
        pausedByMainUi = false;
        if (service.isPaused()) service.resumeEmulator();
    }

    @Override
    public boolean liftOwnPause() {
        if (pausedByMainUi || !isRunning() || !service.isPaused()) return false;
        service.resumeEmulator();
        return true;
    }

    @Override
    public void restoreOwnPause() {
        if (isRunning() && !service.isPaused()) service.pauseEmulator();
        pausedByMainUi = false; // (the user's pause again, even if MainUI paused meanwhile)
    }

    private File resumeFile() {
        return new File(service.mainuiGamePrefs().getUserSaveDir(), "MainUI/resume.st");
    }

    @Override
    public String save(int slot) throws Exception {
        if (slot == MainUIStateApi.RESUME_SLOT) {
            final File f = resumeFile();
            //noinspection ResultOfMethodCallIgnored
            f.getParentFile().mkdirs();
            whenDone(true, () -> {
                RetroAchievementsManager.saveProgress(f.getAbsolutePath());
                core().emuSaveFile(f.getAbsolutePath());
            });
            return f.getAbsolutePath();
        }
        final int previous = service.getSlot();
        core().emuSetSlot(slot);
        try {
            whenDone(true, service::saveSlot);
        } finally {
            core().emuSetSlot(previous);
        }
        File f = slotFile(slot);
        return f != null ? f.getAbsolutePath() : "";
    }

    @Override
    public void load(int slot) throws Exception {
        if (slot == MainUIStateApi.RESUME_SLOT) {
            final File f = resumeFile();
            if (!f.isFile()) throw new IllegalStateException("there is no resume state");
            whenDone(false, () -> service.loadState(f));
            return;
        }
        final int previous = service.getSlot();
        core().emuSetSlot(slot);
        try {
            whenDone(false, service::loadSlot);
        } finally {
            core().emuSetSlot(previous);
        }
    }

    @Override
    public long slotTime(int slot) {
        File f = slot == MainUIStateApi.RESUME_SLOT ? resumeFile() : slotFile(slot);
        return f != null && f.isFile() ? f.lastModified() : 0;
    }

    /** The core's file for [slot] ("<good name>.st<slot>" in the game's slot folder), the newest. */
    private File slotFile(int slot) {
        File[] files = new File(service.mainuiGamePrefs().getSlotSaveDir()).listFiles((d, n) -> n.endsWith(".st" + slot));
        File best = null;
        if (files != null) for (File f : files) if (best == null || f.lastModified() > best.lastModified()) best = f;
        return best;
    }

    /**
     * Runs [start] and waits (30 s of running at most) for the core's save / load complete
     * callback. The core does the job only while the game runs: time paused doesn't count, so a
     * job held back by a pause is never given up on (and the slot put back under it).
     */
    private void whenDone(boolean save, Runnable start) throws Exception {
        final int param = (save ? CoreTypes.m64p_core_param.M64CORE_STATE_SAVECOMPLETE : CoreTypes.m64p_core_param.M64CORE_STATE_LOADCOMPLETE).ordinal();
        final CountDownLatch done = new CountDownLatch(1);
        final int[] result = {0};
        CoreInterface.OnStateCallbackListener l = (paramChanged, newValue) -> {
            if (paramChanged == param) { result[0] = newValue; done.countDown(); }
        };
        core().addOnStateCallbackListener(l);
        try {
            start.run();
            long waited = 0;
            while (!done.await(250, TimeUnit.MILLISECONDS)) {
                if (!isRunning()) throw new IllegalStateException("the game ended");
                if (!service.isPaused() && (waited += 250) >= 30_000) throw new IllegalStateException("Mupen64Plus didn't finish " + (save ? "saving" : "loading"));
            }
        } finally {
            core().removeOnStateCallbackListener(l);
        }
        if (result[0] != 1) throw new IllegalStateException("Mupen64Plus couldn't " + (save ? "save" : "load") + " the state");
    }
}
