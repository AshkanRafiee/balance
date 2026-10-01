package com.ashkanrafiee.balance;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ashkanrafiee.balance.parser.LocalPackStore;
import com.ashkanrafiee.balance.parser.PackDocument;
import com.ashkanrafiee.balance.parser.Rules;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The rules the reader brought or wrote: which packs are on this device, importing one, exporting
 * one back out, and removing one.
 *
 * <p>Everything here happens on the device. A pack is read from a file the reader chose, checked
 * against the same schema and the same strict reader that read the shipped ones, and written to
 * app-private storage; nothing is fetched, nothing is uploaded, and the app asks for no permission to
 * do any of it. Exporting writes a file where the reader chooses, and shows what was written before
 * it is.
 *
 * <p>Nothing is installed from a file without being shown first. The preview says which bank the
 * pack claims, what it would do to the copy already on the device -- install, update, change
 * nothing, or refuse -- and, where the pack is an older revision than the one held, says that too,
 * because going back to an older pack is a decision rather than an update and gets its own word.
 *
 * <p>A pack is shown with the name it carries, not with the name of the bank it may have come from,
 * so a reader can tell their own rule from the shipped one it was derived from.
 */
public final class LocalPacksActivity extends ThemedScreenActivity {

    private static final int REQ_IMPORT = 401;
    private static final int REQ_EXPORT = 402;
    private static final String TYPE_JSON = "application/json";

    private EngineRules engine;

    @Override String title() {
        return getString(R.string.local_packs_title);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        engine = EngineRules.activate(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        // A pack may have arrived from a share sheet or a file manager while this screen was in the
        // background, and the list must never disagree with what the engine is actually reading.
        if (engine == null) engine = EngineRules.activate(this);
        else engine.refreshLocal();
        redraw();
    }

    @Override
    void render() {
        TextView note = text(getString(R.string.local_packs_note), 12, muted);
        note.setLineSpacing(2, 1.05f);
        body.addView(note, margin(2, 0, 2, 10));

        List<EngineRules.Bank> banks = engine == null ? new ArrayList<>() : engine.localBanks();
        if (banks.isEmpty()) {
            TextView empty = text(getString(R.string.local_packs_empty), 14, muted);
            empty.setLineSpacing(2, 1.05f);
            body.addView(empty, margin(2, dp(18), 2, dp(10)));
        }
        for (EngineRules.Bank bank : banks) packRow(bank.id);

        // A stored pack the engine cannot read is worth naming: the reader put it here, and an app
        // that silently ignores it looks like it lost their work.
        List<String> unreadable = engine == null ? List.of() : engine.unreadableLocal();
        if (!unreadable.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (String packId : unreadable) {
                if (names.length() > 0) names.append(", ");
                names.append(packId);
            }
            TextView broken = text(getString(R.string.local_packs_unreadable, names.toString()),
                12, muted);
            broken.setLineSpacing(2, 1.05f);
            body.addView(broken, margin(2, dp(18), 2, dp(10)));
        }

        LinearLayout add = cardRow();
        add.addView(text(getString(R.string.local_packs_import), 15, accent),
            new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(add, margin(0, dp(12), 0, dp(20)));
        add.setOnClickListener(v -> pickFile(TYPE_JSON, REQ_IMPORT));
    }

    /** One pack: the bank it reads, the name it is stored under, and the two things a reader can do
     *  with it. The id is shown because it is what an exported file is called and what a diagnostic
     *  report quotes, and a reader comparing two packs needs it. */
    private void packRow(String packId) {
        LinearLayout line = cardRow();
        body.addView(line, margin(0, 6, 0, 0));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(BankRules.displayName(this,
            engine.bankNameOf(packId) == null ? packId : engine.bankNameOf(packId)), 14, fg);
        name.setMaxLines(2);
        column.addView(name);
        TextView id = text(packId, 11, muted);
        id.setMaxLines(1);
        column.addView(id);
        line.addView(column, new LinearLayout.LayoutParams(0, -2, 1));

        TextView export = action(getString(R.string.local_packs_export));
        line.addView(export);
        export.setOnClickListener(v -> {
            exporting = packId;
            createFile(TYPE_JSON, exportName(packId), REQ_EXPORT);
        });

        TextView remove = action(getString(R.string.local_packs_remove));
        line.addView(remove);
        remove.setOnClickListener(v -> confirmRemove(packId));
    }

    // ---- import ----

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQ_IMPORT) previewImported(data.getData());
        else if (requestCode == REQ_EXPORT) writeExport(data.getData(), exporting);
    }

    /** The pack an export was opened for, carried between the picker and the write. */
    private String exporting;

    /** Stages the chosen file and shows what it is before anything is installed. A file the store
     *  refuses never reaches the device's rules at all, and the refusal says which of the reasons it
     *  was: a reader whose file did not load deserves to know it was the file. */
    private void previewImported(Uri uri) {
        LocalPackStore.Pack staged;
        try {
            LocalPackStore store = EngineRules.localStore(this);
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("unreadable");
                staged = store.stage(input);
            }
        } catch (LocalPackStore.Failure refused) {
            toast(local_packs_refusal(refused.code));
            return;
        } catch (IOException unreadable) {
            toast(R.string.local_packs_failed_reading);
            return;
        }
        PackDocument document = staged.document();
        // What installing would do, asked of the store rather than decided here, so the words on
        // this screen and the decision that follows it come from one implementation of the table.
        LocalPackStore store = localStore();
        LocalPackStore.Pack held = held(document.id(), store);
        String consequence;
        boolean goBack;
        try {
            LocalPackStore.Outcome outcome = store == null ? null : store.preview(staged, false);
            goBack = false;
            if (held == null) consequence = getString(R.string.local_packs_would_install);
            else if (outcome == LocalPackStore.Outcome.NO_OP)
                consequence = getString(R.string.local_packs_would_noop);
            else consequence = getString(R.string.local_packs_would_update,
                held.revision(), document.revision());
        } catch (LocalPackStore.Failure refused) {
            goBack = refused.code == LocalPackStore.Code.DOWNGRADE;
            String current = held == null ? "" : held.revision();
            consequence = getString(refused.code == LocalPackStore.Code.DOWNGRADE
                    ? R.string.local_packs_would_downgrade : R.string.local_packs_would_conflict,
                current, document.revision());
        } catch (IOException unavailable) {
            goBack = false;
            consequence = getString(R.string.local_packs_failed_storing);
        }
        String forks = upstream(document.id());
        String message = getString(R.string.local_packs_preview,
            BankRules.displayName(this, document.bank().name()),
            document.templates().size(),
            senderCount(document),
            document.id(),
            document.revision(),
            forks.isEmpty() ? getString(R.string.local_packs_preview_own)
                : getString(R.string.local_packs_preview_fork, forks),
            consequence);
        // An older revision is refused unless the reader says so, so the button that goes back is
        // the one that says it is going back -- not the same button that installs an update.
        final boolean revert = goBack;
        new AlertDialog.Builder(this)
            .setTitle(R.string.local_packs_preview_title)
            .setMessage(message)
            .setPositiveButton(revert ? R.string.local_packs_revert : R.string.local_packs_install,
                (d, w) -> install(staged, revert))
            .setNegativeButton(R.string.local_packs_cancel, null)
            .show();
    }

    /** The catalog pack a local id was derived from, or an empty string when there is none. The
     *  namespace prefix already says where it came from; this is the same thing in words, because
     *  the reader is being told, not shown a prefix. */
    private String upstream(String localId) {
        if (!localId.startsWith(LocalPackStore.NAMESPACE)
                || localId.length() <= LocalPackStore.NAMESPACE.length()) return "";
        String upstream = localId.substring(LocalPackStore.NAMESPACE.length());
        return engine == null || engine.bankNameOf(upstream) == null ? "" : upstream;
    }

    private static int senderCount(PackDocument document) {
        java.util.Set<String> senders = new java.util.LinkedHashSet<>();
        for (Rules.Template template : document.templates()) senders.addAll(template.senders());
        return senders.size();
    }

    private LocalPackStore.Pack held(String packId, LocalPackStore store) {
        try {
            return store.snapshot().find(packId);
        } catch (IOException unavailable) {
            return null;
        }
    }

    /** The store, or null when even the directory cannot be opened -- which the screen shows as an
     *  empty list rather than as a failure, because there is nothing the reader can do about it here. */
    private LocalPackStore localStore() {
        try {
            return EngineRules.localStore(this);
        } catch (IOException unavailable) {
            return null;
        }
    }

    private void install(LocalPackStore.Pack staged, boolean revert) {
        try {
            LocalPackStore store = localStore();
            if (store == null) throw new IOException("unavailable");
            LocalPackStore.Result result = store.install(staged, revert);
            if (engine != null) engine.refreshLocal();
            toast(local_packs_result(result.outcome()));
            redraw();
        } catch (LocalPackStore.Failure refused) {
            toast(local_packs_refusal(refused.code));
        } catch (IOException failure) {
            toast(R.string.local_packs_failed_storing);
        }
    }

    private int local_packs_result(LocalPackStore.Outcome outcome) {
        switch (outcome) {
            case NO_OP: return R.string.local_packs_kept;
            case UPDATED: return R.string.local_packs_updated;
            default: return R.string.local_packs_installed;
        }
    }

    private int local_packs_refusal(LocalPackStore.Code code) {
        switch (code) {
            case CONFLICT: return R.string.local_packs_error_conflict;
            case DOWNGRADE: return R.string.local_packs_error_downgrade;
            case FULL: return R.string.local_packs_error_full;
            case UNREADABLE: return R.string.local_packs_failed_storing;
            default: return R.string.local_packs_error_malformed;
        }
    }

    /** An older revision is not an update. Removing a pack is not reversible either, so both are
     *  asked about before they happen rather than after. */
    private void confirmRemove(String packId) {
        new AlertDialog.Builder(this)
            .setTitle(R.string.local_packs_remove)
            .setMessage(getString(R.string.local_packs_remove_confirm,
                engine == null || engine.bankNameOf(packId) == null ? packId : engine.bankNameOf(packId)))
            .setPositiveButton(R.string.local_packs_remove, (d, w) -> {
                try {
                    LocalPackStore store = localStore();
                    if (store == null) throw new IOException("unavailable");
                    store.remove(packId);
                    if (engine != null) engine.refreshLocal();
                    redraw();
                } catch (IOException failure) {
                    toast(R.string.local_packs_failed_storing);
                }
            })
            .setNegativeButton(R.string.local_packs_cancel, null)
            .show();
    }

    // ---- export ----

    private void writeExport(Uri uri, String packId) {
        if (packId == null) return;
        LocalPackStore store = localStore();
        LocalPackStore.Pack pack = store == null ? null : held(packId, store);
        if (pack == null) {
            toast(R.string.local_packs_failed_reading);
            return;
        }
        // The canonical bytes the store holds, so an exported pack is byte-identical to the one
        // installed and re-importing it changes nothing.
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("unwritable");
            out.write(pack.canonical());
        } catch (IOException | SecurityException unwritable) {
            toast(R.string.local_packs_failed_writing);
        }
    }

    /** Names an exported file after the pack, which is also the name the app would read it back
     *  under. Lowercase, because a pack id is lowercase and a reader looking for the file later
     *  will type what the app showed them. */
    static String exportName(String packId) {
        return packId.toLowerCase(Locale.ROOT) + ".pack.json";
    }
}