package com.ashkanrafiee.balance;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The last screen before anything leaves the device: the exact text, editable, with the numbers
 * optionally rewritten.
 *
 * <p>Everything before this asks the reader to trust that what gets sent is what they chose. This
 * screen exists because that trust should not be required. It shows the whole document — every
 * message, every reading, every rule name, in the words and line breaks that will be sent — and lets
 * the reader change any of it before it goes. Redacting the numbers is one tap away and preserves
 * their length, so a redacted sample still exercises the rule it was reported for.
 *
 * <p>Redaction is assistance, not a guarantee, and the screen says so: a merchant, a street or a
 * person's name can be worth more to a stranger than a number is. The reader decides what is
 * shareable, which is why this is an editable preview and not a one-way redaction pipeline.
 *
 * <p>Nothing here is written to disk and the text is not kept in saved instance state: a document
 * with raw bank messages in it must not come back in a restored activity. The clipboard copy is
 * cleared again once the paste window has passed, exactly as the chooser's own copy is.
 */
public final class SharePreviewActivity extends ThemedScreenActivity {

    static final String EXTRA_SUBJECT = "subject";
    static final String EXTRA_REPORT = "report";
    /** Whether the numbers are already rewritten, so returning to the preview opens in the state
     *  the reader last left it rather than in the state they first saw. */
    static final String EXTRA_REDACTED = "redacted";

    private static final String TAG = "SharePreview";
    /** Long enough to paste a report into a mail draft, short enough that a forgotten clipboard
     *  entry is not a readable archive of someone's bank messages. */
    private static final long CLIP_CLEAR_MS = 15_000L;
    private static final Handler HANDLER = new Handler(android.os.Looper.getMainLooper());

    private String subject = "";
    private String report = "";
    /** What the text was before the numbers were rewritten, so unchecking restores the reader's
     *  own wording exactly rather than a second pass of the redaction. */
    private String beforeRedaction;
    private EditText editor;
    private CheckBox hide;
    private Runnable clearClip;

    @Override
    public void onCreate(Bundle state) {
        Intent intent = getIntent();
        subject = intent == null ? "" : String.valueOf(intent.getStringExtra(EXTRA_SUBJECT));
        report = intent == null ? "" : String.valueOf(intent.getStringExtra(EXTRA_REPORT));
        boolean redacted = intent != null && intent.getBooleanExtra(EXTRA_REDACTED, false);
        if (redacted) {
            beforeRedaction = report;
            report = Redactor.redact(report);
        }
        super.onCreate(state);
    }

    @Override
    String title() {
        return getString(R.string.share_preview_title);
    }

    @Override
    void render() {
        TextView explain = text(getString(R.string.share_preview_explain), 13, muted);
        explain.setLineSpacing(2, 1.05f);
        body.addView(explain, margin(0, 0, 0, 12));

        LinearLayout toggle = cardRow();
        hide = new CheckBox(this);
        hide.setText(getString(R.string.share_preview_hide_numbers));
        hide.setTextColor(fg);
        hide.setTextSize(14);
        hide.setChecked(beforeRedaction != null);
        hide.setOnCheckedChangeListener((b, checked) -> redact(checked));
        toggle.addView(hide);
        body.addView(toggle, margin(0, 0, 0, 10));
        body.addView(document(), margin(0, 0, 0, 0));
        actions();
    }

    /** The document itself: monospace, on a card, filling the column. The input type asks for no
     *  suggestions and no spell checking, because a bank message is not prose and a keyboard that
     *  "corrects" it would silently change the very thing under review. */
    private LinearLayout document() {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        box.setBackground(rounded(card, 13));
        editor = new EditText(this);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setTextSize(12);
        editor.setTextColor(fg);
        editor.setHintTextColor(muted);
        editor.setBackgroundColor(0x00000000);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setText(report);
        // Raw bank messages must not survive into saved state: a restored activity would put them
        // back on the display and into the state bundle a screenshot or a backup could carry.
        editor.setSaveEnabled(false);
        box.addView(editor, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    private void actions() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView copy = text(getString(R.string.share_preview_copy), 13, fg);
        copy.setGravity(Gravity.CENTER);
        copy.setPadding(dp(14), dp(11), dp(14), dp(11));
        copy.setMinHeight(dp(48));
        copy.setBackground(rounded(hero, 13));
        copy.setOnClickListener(v -> copy());
        row.addView(copy);

        TextView send = text(getString(R.string.share_preview_send), 14, bg);
        send.setGravity(Gravity.CENTER);
        send.setTypeface(null, Typeface.BOLD);
        send.setPadding(dp(18), dp(11), dp(18), dp(11));
        send.setMinHeight(dp(48));
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(0, -2, 1);
        sendParams.setMarginStart(dp(10));
        send.setBackground(rounded(accent, 13));
        send.setOnClickListener(v -> send());
        row.addView(send, sendParams);
        body.addView(row, margin(0, 14, 0, 0));
    }

    /** Rewrites the digits in the document, or puts back exactly what was there before. */
    private void redact(boolean on) {
        if (on) {
            beforeRedaction = editor.getText().toString();
            editor.setText(Redactor.redact(beforeRedaction));
        } else if (beforeRedaction != null) {
            editor.setText(beforeRedaction);
            beforeRedaction = null;
        }
    }

    private void copy() {
        String text = editor.getText().toString();
        if (text.isEmpty()) {
            toast(R.string.share_preview_empty);
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(subject, text));
        if (clearClip != null) HANDLER.removeCallbacks(clearClip);
        clearClip = () -> {
            clearClip = null;
            try {
                CharSequence current = clipboard.hasPrimaryClip()
                        && clipboard.getPrimaryClip() != null
                        && clipboard.getPrimaryClip().getItemCount() > 0
                        ? clipboard.getPrimaryClip().getItemAt(0).getText() : null;
                // Only clear our own report: a clipboard the reader has since replaced is theirs.
                if (text.equals(String.valueOf(current))) {
                    if (android.os.Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip();
                    else clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
                }
            } catch (Exception denied) {
                // Android 10+ can refuse to read a clip another app took; treat that as cleared.
                Log.w(TAG, "clipboard read failed", denied);
            }
        };
        HANDLER.postDelayed(clearClip, CLIP_CLEAR_MS);
        Toast.makeText(this, R.string.share_preview_copied, Toast.LENGTH_SHORT).show();
    }

    /** Opens a prefilled mail to the maintainer. The system chooser is the final approval: nothing is
     *  sent until the reader picks an app and presses send there themselves. */
    private void send() {
        if (editor.getText().length() == 0) {
            toast(R.string.share_preview_empty);
            return;
        }
        try {
            Intent mail = new Intent(Intent.ACTION_SENDTO, Uri.parse(mailto()));
            mail.putExtra(Intent.EXTRA_SUBJECT, subject);
            mail.putExtra(Intent.EXTRA_TEXT, editor.getText().toString());
            startActivity(Intent.createChooser(mail, getString(R.string.sender_share_send_via)));
        } catch (Exception noMail) {
            Log.w(TAG, "no mail app; falling back to the share sheet");
            try {
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_SUBJECT, subject);
                share.putExtra(Intent.EXTRA_TEXT, editor.getText().toString());
                startActivity(Intent.createChooser(share, getString(R.string.sender_share_send_via)));
            } catch (Exception noTarget) {
                Log.w(TAG, "no share target at all");
            }
        }
    }

    /** The recipient lives in the URI's path so it reaches the compose draft on every client, and
     *  the subject and body are carried in the URI as well as in the extras, because mail clients
     *  disagree about which of the two they read. */
    private String mailto() {
        return SenderShareActivity.mailToUri(subject, editor.getText().toString());
    }

    @Override
    protected void onDestroy() {
        if (clearClip != null) HANDLER.removeCallbacks(clearClip);
        super.onDestroy();
    }
}