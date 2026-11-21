package com.jpsums.watchlater;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The popup you see after tapping Share > WatchLater+ in Facebook, YouTube, TikTok or any app.
 * It slides up over that app, asks which folder, saves the video and closes,
 * so you stay where you were.
 */
public class ShareActivity extends Activity {

    private static final Pattern URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private String url = "";
    private String title = "";
    private boolean night = true;
    private int cSheet, cText, cMuted, cTile, cAccent;

    @SuppressWarnings("deprecation")
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

        // draw the dimmed background behind the status bar too
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        getWindow().setNavigationBarColor(cSheet);

        setContentView(buildUi(data));
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
        cAccent = night ? 0xFFFFB22E : 0xFFF5A516;
    }

    /* ---------- the popup ---------- */

    private View buildUi(JSONObject data) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x99000000);
        root.setPadding(0, dp(48), 0, 0);
        root.setOnClickListener(v -> finish());

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClickable(true); // taps on the sheet itself must not close it
        float r = dp(24);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cSheet);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(bg);
        sheet.setPadding(dp(16), dp(10), dp(16), dp(14));
        root.addView(sheet, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM));

        // little grab handle
        View grab = new View(this);
        grab.setBackground(rounded(night ? 0xFF3A3A47 : 0xFFDDD6C8, 2));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(dp(40), dp(4));
        gl.gravity = Gravity.CENTER_HORIZONTAL;
        gl.bottomMargin = dp(14);
        sheet.addView(grab, gl);

        // heading row: brand + close
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("WatchLater+", 13, cAccent, true);
        head.addView(brand, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView close = text("Cancel", 14, cMuted, true);
        close.setPadding(dp(12), dp(8), dp(4), dp(8));
        close.setOnClickListener(v -> finish());
        head.addView(close, new LinearLayout.LayoutParams(WRAP, WRAP));
        sheet.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView h = text("Save to which folder?", 21, cText, true);
        sheet.addView(h, new LinearLayout.LayoutParams(MATCH, WRAP));

        String existing = existingFolderName(data);
        String subText = !title.isEmpty() ? title : prettyHost();
        if (existing != null) subText = "Already saved in " + existing + ". Pick a folder to move it.";
        TextView sub = text(subText, 14, cMuted, false);
        sub.setMaxLines(2);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(MATCH, WRAP);
        sl.topMargin = dp(4);
        sl.bottomMargin = dp(12);
        sheet.addView(sub, sl);

        // folder tiles, two per row, scrolls when there are many
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        JSONArray folders = data.optJSONArray("folders");
        LinearLayout row = null;
        int count = 0;
        if (folders != null) {
            for (int i = 0; i < folders.length(); i++) {
                JSONObject f = folders.optJSONObject(i);
                if (f == null) continue;
                final String id = f.optString("id", "");
                final String name = f.optString("name", "");
                if (id.isEmpty() || name.isEmpty()) continue;
                String emoji = f.optString("emoji", "📁");
                TextView tile = tile(emoji + "  " + name, cTile, cText);
                tile.setOnClickListener(v -> saveTo(id, name));
                if (count % 2 == 0) row = newRow(grid);
                row.addView(tile, tileParams());
                count++;
            }
        }
        TextView add = tile("+  New folder", 0x00000000, cAccent);
        GradientDrawable dashed = new GradientDrawable();
        dashed.setColor(0x00000000);
        dashed.setCornerRadius(dp(16));
        dashed.setStroke(dp(2), night ? 0xFF3A3A47 : 0xFFDDD6C8, dp(6), dp(5));
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

    private String existingFolderName(JSONObject data) {
        JSONArray items = data.optJSONArray("items");
        JSONArray folders = data.optJSONArray("folders");
        if (items == null || folders == null) return null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null || !url.equals(it.optString("url"))) continue;
            String fid = it.optString("folderId");
            for (int j = 0; j < folders.length(); j++) {
                JSONObject f = folders.optJSONObject(j);
                if (f != null && fid.equals(f.optString("id"))) return f.optString("name", "your list");
            }
            return "your list";
        }
        return null;
    }

    /* ---------- saving ---------- */

    private void saveTo(String folderId, String folderName) {
        boolean ok = false;
        try {
            JSONObject data = new JSONObject(Store.readRaw(this));
            JSONArray items = data.optJSONArray("items");
            if (items == null) {
                items = new JSONArray();
                data.put("items", items);
            }
            long now = System.currentTimeMillis();
            JSONObject found = null;
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it != null && url.equals(it.optString("url"))) {
                    found = it;
                    break;
                }
            }
            if (found != null) {
                found.put("folderId", folderId);
                found.put("addedAt", now);
                found.put("watched", false);
                if (found.optString("title").isEmpty() && !title.isEmpty()) found.put("title", title);
            } else {
                JSONObject it = new JSONObject();
                it.put("id", "v" + Long.toString(now, 36) + Integer.toString((int) (Math.random() * 1295), 36));
                it.put("url", url);
                it.put("title", title);
                it.put("folderId", folderId);
                it.put("addedAt", now);
                it.put("watched", false);
                items.put(it);
            }
            ok = Store.writeRaw(this, data.toString());
        } catch (JSONException e) {
            ok = false;
        }
        Toast.makeText(this, ok ? "Saved to " + folderName : "WatchLater+ could not save this", Toast.LENGTH_SHORT).show();
        finish();
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

    private void createFolderAndSave(String name) {
        try {
            JSONObject data = new JSONObject(Store.readRaw(this));
            JSONArray folders = data.optJSONArray("folders");
            if (folders == null) folders = new JSONArray();

            // a folder with the same name already? use it
            for (int i = 0; i < folders.length(); i++) {
                JSONObject f = folders.optJSONObject(i);
                if (f != null && name.equalsIgnoreCase(f.optString("name"))) {
                    saveTo(f.optString("id"), f.optString("name"));
                    return;
                }
            }

            String id = "f" + Long.toString(System.currentTimeMillis(), 36);
            JSONObject nf = Store.folder(id, name, "📁");
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
            if (!Store.writeRaw(this, data.toString())) {
                Toast.makeText(this, "WatchLater+ could not save this", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            saveTo(id, name);
        } catch (JSONException e) {
            Toast.makeText(this, "WatchLater+ could not save this", Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}
