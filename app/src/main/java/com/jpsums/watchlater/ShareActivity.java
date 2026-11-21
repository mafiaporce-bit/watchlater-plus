package com.jpsums.watchlater;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The popup you see after tapping Share > WatchLater+ in Facebook, YouTube, TikTok or any app.
 *
 * When the video's words clearly match a folder it is saved straight away and you only see
 * "Saved to Food" with a Change button. Otherwise it asks which folder, with a box for a
 * short note. Either way it closes by itself and you stay in the app you were in.
 */
public class ShareActivity extends Activity {

    private static final Pattern URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int SCRIM = 0x99000000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private String url = "";
    private String title = "";
    private String itemId = "";
    private String existingFolder = null; // name of the folder this link is already in
    private String fetchedText = "";      // title + description that arrived from the web
    private String suggestedId = "";
    private boolean changing = false;     // you tapped "Change folder" after an auto-save
    private boolean askShown = false;

    private boolean night = true;
    private int cSheet, cText, cMuted, cTile, cAccent, cLine;

    private EditText noteInput;
    private TextView subView, hintView;
    private final Map<String, TextView> tiles = new HashMap<String, TextView>();
    private final Map<String, String> tileNames = new HashMap<String, String>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!readShare(getIntent())) {
            Toast.makeText(this, "WatchLater+: there is no link to save here", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        JSONObject data = load();
        pickColours(data);
        getWindow().setStatusBarColor(SCRIM);
        getWindow().setNavigationBarColor(cSheet);
        findExisting(data);

        // start getting the title and thumbnail right away, while you choose
        Previews.fetch(this, itemId, url, title, (id, t, d) -> runOnUiThread(() -> onPreview(t, d)));

        JSONObject settings = data.optJSONObject("settings");
        boolean auto = settings == null || settings.optBoolean("autoSort", true);
        Sorter.Pick pick = Store.suggest(this, data, title, url);
        suggestedId = pick.id;

        if (existingFolder == null && auto && pick.sure) {
            String name = folderLabel(data, pick.id);
            if (name != null && save(pick.id, false, "")) {
                setContentView(buildSaved(name));
                handler.postDelayed(this::finish, 3500);
                return;
            }
        }
        showAsk(data);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /* ---------- reading what was shared ---------- */

    private boolean readShare(Intent in) {
        if (in == null || !Intent.ACTION_SEND.equals(in.getAction())) return false;
        CharSequence cs = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
        String text = cs == null ? "" : cs.toString();
        String subject = clean(in.getStringExtra(Intent.EXTRA_SUBJECT));
        String extraTitle = clean(in.getStringExtra(Intent.EXTRA_TITLE));

        String rest = "";
        Matcher m = URL.matcher(text);
        if (m.find()) {
            url = m.group();
            rest = clean(text.substring(0, m.start()) + " " + text.substring(m.end()));
        } else {
            Matcher m2 = URL.matcher(subject);
            if (!m2.find()) return false;
            url = m2.group();
            subject = clean(subject.substring(0, m2.start()) + " " + subject.substring(m2.end()));
        }
        // drop punctuation that got glued to the end of the link
        while (url.length() > 10 && ".,;:!?)]}>\"'".indexOf(url.charAt(url.length() - 1)) >= 0) {
            url = url.substring(0, url.length() - 1);
        }

        if (!subject.isEmpty()) title = subject;
        else if (!extraTitle.isEmpty()) title = extraTitle;
        else title = rest;
        if (URL.matcher(title).find()) title = clean(URL.matcher(title).replaceAll(" "));
        if (title.length() > 200) title = title.substring(0, 200);
        return true;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private JSONObject load() {
        try {
            return new JSONObject(Store.readRaw(this));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private void pickColours(JSONObject data) {
        String pref = "auto";
        JSONObject settings = data.optJSONObject("settings");
        if (settings != null) pref = settings.optString("theme", "auto");
        boolean systemNight = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        night = "dark".equals(pref) || (!"light".equals(pref) && systemNight);
        cSheet = night ? 0xFF1A1A21 : 0xFFFFFFFF;
        cText = night ? 0xFFF3F1EC : 0xFF17161C;
        cMuted = night ? 0xFF9C9AA6 : 0xFF6D6A75;
        cTile = night ? 0xFF2A2A35 : 0xFFEFEADF;
        cAccent = night ? 0xFFFFB22E : 0xFFE08A00;
        cLine = night ? 0xFF3A3A47 : 0xFFDDD6C8;
    }

    /** Is this link already saved? Then reuse its id and remember where it is. */
    private void findExisting(JSONObject data) {
        JSONArray items = data.optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null || !url.equals(it.optString("url"))) continue;
            itemId = it.optString("id");
            String label = folderLabel(data, it.optString("folderId"));
            existingFolder = label == null ? "your list" : label;
            break;
        }
        if (itemId.isEmpty()) {
            itemId = "v" + Long.toString(System.currentTimeMillis(), 36)
                    + Integer.toString((int) (Math.random() * 1295), 36);
        }
    }

    /** "🍔 Food" for a folder id, or null when there is no such folder. */
    private static String folderLabel(JSONObject data, String id) {
        JSONArray folders = data.optJSONArray("folders");
        for (int i = 0; folders != null && i < folders.length(); i++) {
            JSONObject f = folders.optJSONObject(i);
            if (f != null && id.equals(f.optString("id"))) {
                return (f.optString("emoji", "📁") + " " + f.optString("name")).trim();
            }
        }
        return null;
    }

    /* ---------- the title arrived from the web ---------- */

    private void onPreview(String t, String d) {
        if (isFinishing() || isDestroyed() || t == null || t.isEmpty()) return;
        fetchedText = (t + " " + (d == null ? "" : d)).trim();
        if (!askShown) return;
        if (title.isEmpty() && existingFolder == null && subView != null) subView.setText(t);
        Sorter.Pick pick = Store.suggest(this, load(), title + " " + fetchedText, url);
        if (!pick.id.isEmpty() && !pick.id.equals(suggestedId)) {
            suggestedId = pick.id;
            markSuggestion();
        }
    }

    private void markSuggestion() {
        for (Map.Entry<String, TextView> e : tiles.entrySet()) {
            boolean on = e.getKey().equals(suggestedId);
            GradientDrawable g = rounded(cTile, 16);
            if (on) g.setStroke(dp(2), cAccent);
            e.getValue().setBackground(g);
        }
        if (hintView != null) {
            String name = tileNames.get(suggestedId);
            hintView.setVisibility(name == null ? View.GONE : View.VISIBLE);
            if (name != null) hintView.setText("Looks like " + name);
        }
    }

    /* ---------- the popup ---------- */

    private LinearLayout newSheet(FrameLayout root) {
        root.setBackgroundColor(SCRIM);
        root.setOnClickListener(v -> finish());

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClickable(true);             // taps on the sheet itself must not close it
        sheet.setFocusable(true);             // and it takes the focus, so the keyboard stays down
        sheet.setFocusableInTouchMode(true);
        float r = dp(24);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cSheet);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(bg);
        sheet.setPadding(dp(16), dp(10), dp(16), dp(14));
        root.addView(sheet, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM));

        View grab = new View(this);
        grab.setBackground(rounded(cLine, 2));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(dp(40), dp(4));
        gl.gravity = Gravity.CENTER_HORIZONTAL;
        gl.bottomMargin = dp(14);
        sheet.addView(grab, gl);
        return sheet;
    }

    /** Small "Saved to Food" confirmation after an auto-save. Closes by itself. */
    private View buildSaved(String folderName) {
        FrameLayout root = new FrameLayout(this);
        LinearLayout sheet = newSheet(root);

        sheet.addView(text("WatchLater+  •  sorted for you", 13, cAccent, true), new LinearLayout.LayoutParams(MATCH, WRAP));
        sheet.addView(text("Saved to " + folderName, 22, cText, true), new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView sub = text(title.isEmpty() ? prettyHost() : title, 14, cMuted, false);
        sub.setMaxLines(2);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(MATCH, WRAP);
        sl.topMargin = dp(4);
        sl.bottomMargin = dp(12);
        sheet.addView(sub, sl);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView change = tile("Change folder", cTile, cText);
        change.setGravity(Gravity.CENTER);
        change.setOnClickListener(v -> {
            handler.removeCallbacksAndMessages(null);
            changing = true;
            showAsk(load());
        });
        row.addView(change, tileParams());
        TextView done = tile("Done", cAccent, 0xFF1B1404);
        done.setGravity(Gravity.CENTER);
        done.setOnClickListener(v -> finish());
        row.addView(done, tileParams());
        sheet.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        return root;
    }

    private void showAsk(JSONObject data) {
        setContentView(buildAsk(data));
        askShown = true;
        markSuggestion();
    }

    /** "Save to which folder?" with a note box and the folder tiles. */
    private View buildAsk(JSONObject data) {
        tiles.clear();
        tileNames.clear();
        FrameLayout root = new FrameLayout(this);
        LinearLayout sheet = newSheet(root);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("WatchLater+", 13, cAccent, true), new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView close = text(changing ? "Keep it" : "Cancel", 14, cMuted, true);
        close.setPadding(dp(12), dp(8), dp(4), dp(8));
        close.setOnClickListener(v -> finish());
        head.addView(close, new LinearLayout.LayoutParams(WRAP, WRAP));
        sheet.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));

        boolean moving = changing || existingFolder != null;
        sheet.addView(text(moving ? "Move to which folder?" : "Save to which folder?", 21, cText, true),
                new LinearLayout.LayoutParams(MATCH, WRAP));

        String subText = !title.isEmpty() ? title : prettyHost();
        if (title.isEmpty() && !fetchedText.isEmpty()) subText = fetchedText;
        if (existingFolder != null && !changing) subText = "Already saved in " + existingFolder + ". Pick a folder to move it.";
        subView = text(subText, 14, cMuted, false);
        subView.setMaxLines(2);
        subView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(MATCH, WRAP);
        sl.topMargin = dp(4);
        sheet.addView(subView, sl);

        hintView = text("", 13, cAccent, true);
        hintView.setVisibility(View.GONE);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(MATCH, WRAP);
        hl.topMargin = dp(4);
        sheet.addView(hintView, hl);

        // optional note, so you know later what the video was
        noteInput = new EditText(this);
        noteInput.setHint("Add a note (optional), e.g. pork belly recipe");
        noteInput.setSingleLine(true);
        noteInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        noteInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        noteInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        noteInput.setTextSize(15);
        noteInput.setTextColor(cText);
        noteInput.setHintTextColor(cMuted);
        GradientDrawable nb = rounded(0x00000000, 14);
        nb.setStroke(dp(2), cLine);
        noteInput.setBackground(nb);
        noteInput.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(MATCH, dp(48));
        nl.setMargins(dp(4), dp(12), dp(4), dp(8));
        sheet.addView(noteInput, nl);

        // folder tiles, two per row, scrolls when there are many
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        JSONArray folders = data.optJSONArray("folders");
        LinearLayout row = null;
        int count = 0;
        for (int i = 0; folders != null && i < folders.length(); i++) {
            JSONObject f = folders.optJSONObject(i);
            if (f == null) continue;
            final String id = f.optString("id", "");
            final String name = f.optString("name", "");
            if (id.isEmpty() || name.isEmpty()) continue;
            final String label = (f.optString("emoji", "📁") + "  " + name);
            TextView tile = tile(label, cTile, cText);
            tile.setOnClickListener(v -> pick(id, name));
            tiles.put(id, tile);
            tileNames.put(id, name);
            if (count % 2 == 0) row = newRow(grid);
            row.addView(tile, tileParams());
            count++;
        }
        TextView add = tile("+  New folder", 0x00000000, cAccent);
        GradientDrawable dashed = new GradientDrawable();
        dashed.setColor(0x00000000);
        dashed.setCornerRadius(dp(16));
        dashed.setStroke(dp(2), cLine, dp(6), dp(5));
        add.setBackground(dashed);
        add.setOnClickListener(v -> askNewFolder());
        if (count % 2 == 0) row = newRow(grid);
        row.addView(add, tileParams());
        count++;
        if (count % 2 == 1) row.addView(new View(this), tileParams()); // keep the last tile half width

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(grid, new FrameLayout.LayoutParams(MATCH, WRAP));
        sheet.addView(scroll, new LinearLayout.LayoutParams(MATCH, WRAP));
        return root;
    }

    private LinearLayout newRow(LinearLayout grid) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        grid.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        return row;
    }

    private LinearLayout.LayoutParams tileParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(58), 1f);
        p.setMargins(dp(4), dp(4), dp(4), dp(4));
        return p;
    }

    private TextView tile(String label, int bgColour, int textColour) {
        TextView t = text(label, 16, textColour, true);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(14), 0, dp(10), 0);
        t.setBackground(rounded(bgColour, 16));
        return t;
    }

    private TextView text(String s, int sp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(colour);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable rounded(int colour, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(colour);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String prettyHost() {
        try {
            String h = Uri.parse(url).getHost();
            if (h == null) return url;
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Exception e) {
            return url;
        }
    }

    /* ---------- saving ---------- */

    /** You tapped a folder yourself. */
    private void pick(String folderId, String folderName) {
        String note = noteInput == null ? "" : clean(noteInput.getText().toString());
        boolean ok = save(folderId, true, note);
        if (ok) {
            // remember this choice so the app can make it for you next time
            String words = (title + " " + note + " " + fetchedText).trim();
            Store.learn(this, words, url, folderId, changing ? 2 : 1);
            if (!title.isEmpty() || !fetchedText.isEmpty()) Previews.markLearned(this, itemId);
        }
        Toast.makeText(this, ok ? "Saved to " + folderName : "WatchLater+ could not save this", Toast.LENGTH_SHORT).show();
        finish();
    }

    private boolean save(final String folderId, final boolean manual, final String note) {
        return Store.update(this, data -> {
            JSONArray items = data.optJSONArray("items");
            if (items == null) {
                items = new JSONArray();
                data.put("items", items);
            }
            long now = System.currentTimeMillis();
            JSONObject found = null;
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it != null && (url.equals(it.optString("url")) || itemId.equals(it.optString("id")))) {
                    found = it;
                    break;
                }
            }
            if (found == null) {
                found = new JSONObject();
                found.put("id", itemId);
                found.put("url", url);
                found.put("title", title);
                items.put(found);
            } else if (found.optString("title").isEmpty() && !title.isEmpty()) {
                found.put("title", title);
            }
            found.put("folderId", folderId);
            found.put("addedAt", now);
            found.put("watched", false);
            found.put("manual", manual);
            if (!note.isEmpty()) found.put("note", note);
            return true;
        });
    }

    private void askNewFolder() {
        final EditText input = new EditText(this);
        input.setHint("Folder name, e.g. Travel");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(input, new FrameLayout.LayoutParams(MATCH, WRAP));

        int style = night ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        AlertDialog dialog = new AlertDialog.Builder(this, style)
                .setTitle("New folder")
                .setView(wrap)
                .setPositiveButton("Save here", (d, which) -> {
                    String name = clean(input.getText().toString());
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Give the folder a name", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (name.length() > 30) name = name.substring(0, 30);
                    createFolderAndSave(name);
                })
                .setNegativeButton("Cancel", null)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.show();
        input.requestFocus();
    }

    private void createFolderAndSave(final String name) {
        final String[] target = {"", name}; // id and name of the folder to save into
        boolean ok = Store.update(this, data -> {
            JSONArray folders = data.optJSONArray("folders");
            if (folders == null) folders = new JSONArray();
            // a folder with the same name already? use it
            for (int i = 0; i < folders.length(); i++) {
                JSONObject f = folders.optJSONObject(i);
                if (f != null && name.equalsIgnoreCase(f.optString("name"))) {
                    target[0] = f.optString("id");
                    target[1] = f.optString("name");
                    return false;
                }
            }
            target[0] = "f" + Long.toString(System.currentTimeMillis(), 36);
            JSONObject nf = Store.folder(target[0], name, "📁");
            // keep "Other" last: put the new folder just before it
            JSONArray out = new JSONArray();
            boolean placed = false;
            for (int i = 0; i < folders.length(); i++) {
                JSONObject f = folders.optJSONObject(i);
                if (f == null) continue;
                if (!placed && "other".equals(f.optString("id"))) {
                    out.put(nf);
                    placed = true;
                }
                out.put(f);
            }
            if (!placed) out.put(nf);
            data.put("folders", out);
            return true;
        });
        if (target[0].isEmpty() || (!ok && target[0].startsWith("f") && !folderExists(target[0]))) {
            Toast.makeText(this, "WatchLater+ could not save this", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        pick(target[0], target[1]);
    }

    private boolean folderExists(String id) {
        return folderLabel(load(), id) != null;
    }
}
