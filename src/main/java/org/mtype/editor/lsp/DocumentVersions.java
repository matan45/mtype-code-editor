package org.mtype.editor.lsp;

import java.util.HashMap;
import java.util.Map;

/**
 * The last document version sent to the server, per URI, so a didChange that is not newer than what
 * the server already holds is dropped instead of reverting its text (MYT-422).
 *
 * <p>Not thread-safe: {@link LspBridge} touches it only on its outgoing thread.
 */
final class DocumentVersions {
    private final Map<String, Integer> lastSent = new HashMap<>();

    /**
     * A didOpen for {@code uri} is being sent: its version becomes the baseline. The baseline is SET,
     * not maxed: after a didOpen the server holds exactly this version, even when it is lower than the
     * previous one (a reopened tab restarts at 1; DiffTab's temporary open uses its own counter).
     */
    void opened(String uri, int version) {
        lastSent.put(uri, version);
    }

    /** A didClose for {@code uri} is being sent: the server forgets the document, and so do we. */
    void closed(String uri) {
        lastSent.remove(uri);
    }

    /**
     * Whether a didChange with {@code version} may be sent. Returns false when the server already holds
     * this version or a newer one; otherwise records {@code version} and returns true. A URI with no
     * recorded version is accepted (nothing to compare against).
     */
    boolean acceptChange(String uri, int version) {
        Integer last = lastSent.get(uri);
        if (last != null && version <= last) return false;
        lastSent.put(uri, version);
        return true;
    }

    /** The last version sent for {@code uri}, or null when none is recorded. */
    Integer lastSent(String uri) {
        return lastSent.get(uri);
    }
}
