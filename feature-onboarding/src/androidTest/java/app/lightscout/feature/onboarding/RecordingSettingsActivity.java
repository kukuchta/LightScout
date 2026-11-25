package app.lightscout.feature.onboarding;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only stand-in for a system / OEM settings page or the browser: records the intent it was started with and
 * finishes at once, so the screen under test resumes (S-07 re-check on return, S-08 back from a page).
 */
public class RecordingSettingsActivity extends Activity {

    /** Every intent this activity received, in order. */
    static final List<Intent> OPENED = new CopyOnWriteArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        OPENED.add(new Intent(getIntent()));
        finish();
    }
}
