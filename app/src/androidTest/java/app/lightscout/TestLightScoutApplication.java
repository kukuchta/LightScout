package app.lightscout;

/**
 * The real {@link LightScoutApplication} (StrictMode, CrashRecorder, lazy container, channels) with
 * {@link DeviceComponents} as leaf-adapter factory: in-memory stores, a recording IF-62 and a gateway without network.
 */
public final class TestLightScoutApplication extends LightScoutApplication {

    /** The components of this process (one container per process). */
    static volatile DeviceComponents components;

    /** IR-108: boot-count checks created / run by the end of {@code Application.onCreate} (Q-67: must be 0). */
    static volatile int bootCountChecksAfterOnCreate = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        bootCountChecksAfterOnCreate = components.bootCountChecksCreated.get() + components.bootCountChecksRun.get();
    }

    @Override
    protected ContainerComponents createComponents() {
        DeviceComponents c = new DeviceComponents(this);
        components = c;
        return c;
    }
}
