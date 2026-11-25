/**
 * {@code :feature-entry} (CMP-73, CP-13): nested graph {@code entry_graph} with S-02 Entry form (create / edit /
 * delete). The feature calls IF-23 {@code EntryWriter} and IF-06 {@code EntryRules} only, through IF-28
 * {@code Dependencies}; the result goes back to S-01 through IF-29 {@code Navigator.finishWithResult} (CP-00 R-4,
 * CP-00A R-38).
 */
package app.lightscout.feature.entry;
