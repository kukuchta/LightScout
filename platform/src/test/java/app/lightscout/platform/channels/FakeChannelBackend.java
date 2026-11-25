package app.lightscout.platform.channels;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory {@link ChannelBackend} that mimics the system: {@code create} of an existing id changes nothing; DND
 * bypass is honoured only when policy access was granted at creation time (the VR-27 platform rule).
 */
final class FakeChannelBackend implements ChannelBackend {

    final Map<String, ExistingChannel> channels = new LinkedHashMap<>();
    final Map<String, ChannelSpec> specs = new LinkedHashMap<>();
    final List<String> calls = new ArrayList<>();
    boolean policyAccess;
    /** Ids whose delete throws {@code SecurityException} (a foreground-service notification uses the channel). */
    final java.util.Set<String> fgsChannels = new java.util.HashSet<>();

    @Override
    public List<ExistingChannel> list() {
        return new ArrayList<>(channels.values());
    }

    @Override
    public void create(String id, ChannelSpec spec) {
        calls.add("create " + id);
        if (channels.containsKey(id)) {
            return;
        }
        channels.put(id, new ExistingChannel(id, spec.importance(), spec.bypassDnd() && policyAccess));
        specs.put(id, spec);
    }

    @Override
    public void delete(String id) {
        calls.add("delete " + id);
        if (fgsChannels.contains(id)) {
            throw new SecurityException("Not allowed to delete channel " + id + " with a foreground service");
        }
        channels.remove(id);
        specs.remove(id);
    }

    @Override
    public boolean notificationPolicyAccessGranted() {
        return policyAccess;
    }

    /** Simulates a channel created by an older app version (e.g. the LOW {@code monitoring} channel). */
    void preExisting(String id, int importance) {
        channels.put(id, new ExistingChannel(id, importance, false));
    }

    /** Simulates a user change in system settings. */
    void userChange(String id, int importance, boolean bypassDnd) {
        channels.put(id, new ExistingChannel(id, importance, bypassDnd));
    }
}
