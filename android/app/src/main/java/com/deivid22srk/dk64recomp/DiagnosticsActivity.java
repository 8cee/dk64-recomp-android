package com.deivid22srk.dk64recomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Tela de LOGS E DIAGNÓSTICO (pedido do usuário):
 *  - opção ATIVAR/DESATIVAR a captura de logs (efeito imediato, mesmo com o
 *    jogo rodando no mesmo processo);
 *  - status da sessão atual;
 *  - COMPARTILHAR o log (sessão atual ou das 5 sessões mais recentes) via
 *    ACTION_SEND + provider content:// próprio (sem AndroidX);
 *  - long-press em um log antigo = apagar.
 *
 * Como chegar aqui (duas entradas):
 *  - Configurações do app: menu do jogo (launcher) -> "Logs de diagnóstico"
 *    (GameOption que abre esta tela via file_bridge/JNI);
 *  - long-press no ícone do app no launcher -> "Logs de diagnóstico"
 *    (shortcut estático em res/xml/shortcuts.xml).
 *
 * UI 100% programática: o app é deliberadamente sem AndroidX e com uma
 * activity só; para uma tela utilitária como esta, código direto evita
 * inflar recursos. Tema herdado: AppTheme (fullscreen preto).
 */
public class DiagnosticsActivity extends Activity {

    private static final int MAX_LISTED = 5;

    private LinearLayout fileListContainer;
    private TextView statusText;
    private TextView rendererModeText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        setContentView(scroll);

        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Título
        root.addView(label("Logs & Diagnostics", 22, Typeface.BOLD));

        // Explicação
        TextView expl = label(
                "This audit build captures logs by default so the first run is recorded automatically. "
                        + "You can turn logging off below at any time without restarting the app. "
                        + "When capture is enabled, the game log is written line by line, including "
                        + "audio, Vulkan/RT64 rendering, driver, lifecycle and crash information. "
                        + "If the app is force-closed or crashes, everything written up to that point "
                        + "remains saved, and each normal session ends with a summary of the most common "
                        + "errors and warnings.\n\n"
                        + "Files are stored in: Android/data/" + getPackageName()
                        + "/files/diagnostics (or the app's internal storage if external storage is unavailable).",
                14, Typeface.NORMAL);
        root.addView(expl);

        root.addView(spacer(16));

        // Toggle ON/OFF
        Switch toggle = new Switch(this);
        toggle.setText("Capture logs");
        toggle.setTextSize(17);
        toggle.setTextColor(Color.WHITE);
        toggle.setChecked(DiagnosticsLogger.isEnabled(this));
        toggle.setOnCheckedChangeListener((b, checked) -> {
            DiagnosticsLogger.setEnabled(getApplicationContext(), checked);
            refresh();
        });
        root.addView(toggle);

        root.addView(spacer(8));

        // Status da sessão
        statusText = label("", 14, Typeface.NORMAL);
        root.addView(statusText);

        root.addView(spacer(12));

        // Renderer A/B test controls. The native side reads renderer_compat.txt
        // during the next process start, before plume creates the Vulkan device.
        root.addView(label("Renderer mode (applies on next launch)", 15, Typeface.BOLD));
        rendererModeText = label("", 14, Typeface.NORMAL);
        root.addView(rendererModeText);

        LinearLayout rendererButtons = new LinearLayout(this);
        rendererButtons.setOrientation(LinearLayout.HORIZONTAL);
        rendererButtons.setGravity(Gravity.START);

        Button rendererAuto = new Button(this);
        rendererAuto.setAllCaps(false);
        rendererAuto.setText("Auto");
        rendererAuto.setOnClickListener(v -> setRendererMode("auto"));
        rendererButtons.addView(rendererAuto);

        Button rendererFull = new Button(this);
        rendererFull.setAllCaps(false);
        rendererFull.setText("Full");
        rendererFull.setOnClickListener(v -> setRendererMode("full"));
        rendererButtons.addView(rendererFull);

        Button rendererLegacy = new Button(this);
        rendererLegacy.setAllCaps(false);
        rendererLegacy.setText("Legacy");
        rendererLegacy.setOnClickListener(v -> setRendererMode("legacy"));
        rendererButtons.addView(rendererLegacy);

        root.addView(rendererButtons);
        root.addView(label("After changing the mode, fully close and reopen the game. "
                + "Auto = GPU detection; Full = normal path; Legacy = Adreno 6xx compatibility.",
                13, Typeface.NORMAL));

        root.addView(spacer(12));

        // Compartilhar sessão atual
        Button shareCurrent = new Button(this);
        shareCurrent.setText("Share current session log");
        shareCurrent.setOnClickListener(v -> {
            File cur = DiagnosticsLogger.currentSessionFile();
            if (cur == null) {
                List<File> files = DiagnosticsLogger.listLogFiles(this);
                if (!files.isEmpty()) share(files.get(0));
                else toastStatus("No logs available yet — enable capture and use the game for a while.");
            } else {
                share(cur);
            }
        });
        root.addView(shareCurrent);

        root.addView(spacer(16));

        // Lista de sessões recentes
        root.addView(label("Recent sessions (tap = share; hold = delete)", 15, Typeface.BOLD));
        fileListContainer = new LinearLayout(this);
        fileListContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(fileListContainer);

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ------------------------------------------------------------------

    private void refresh() {
        if (fileListContainer == null) return;
        File current = DiagnosticsLogger.currentSessionFile();
        boolean enabled = DiagnosticsLogger.isEnabled(this);

        if (rendererModeText != null) {
            rendererModeText.setText("Next launch: " + readRendererMode().toUpperCase(Locale.US));
        }

        if (statusText != null) {
            if (enabled && current != null) {
                statusText.setText("Capture ON — current session: " + current.getName()
                        + " (" + humanSize(current.length()) + ")");
            } else if (!enabled) {
                statusText.setText("Capture OFF — diagnostic events will not be recorded.");
            } else {
                statusText.setText("Capture enabled — the session starts on the next app launch.");
            }
        }

        fileListContainer.removeAllViews();
        List<File> files = DiagnosticsLogger.listLogFiles(this);
        int shown = 0;
        for (File f : files) {
            if (shown >= MAX_LISTED) break;
            final File log = f;
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setText(log.getName() + "  (" + humanSize(log.length()) + ")");
            b.setOnClickListener(v -> share(log));
            b.setOnLongClickListener(v -> { confirmDelete(log); return true; });
            fileListContainer.addView(b, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            shown++;
        }
        if (shown == 0) {
            TextView none = label("No logs yet.", 13, Typeface.ITALIC);
            fileListContainer.addView(none);
        }
    }

    /** ACTION_SEND via content:// do DiagnosticsFilesProvider (sem file://). */
    private void share(File log) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, log.getName());
            send.putExtra(Intent.EXTRA_STREAM,
                    DiagnosticsFilesProvider.shareUri(this, log.getName()));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Share log"));
        } catch (Throwable t) {
            toastStatus("Failed to share: " + t.getMessage());
        }
    }

    private void confirmDelete(File log) {
        new AlertDialog.Builder(this)
                .setTitle("Delete log")
                .setMessage("Delete " + log.getName() + "?")
                .setPositiveButton("Delete", (d, w) -> {
                    try { log.delete(); } catch (Throwable ignored) { }
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------

    private File rendererCompatExternal() {
        File base = getExternalFilesDir(null);
        return (base != null) ? new File(base, "renderer_compat.txt") : null;
    }

    private File rendererCompatInternal() {
        return new File(getFilesDir(), "renderer_compat.txt");
    }

    private String readRendererMode() {
        File[] candidates = new File[]{rendererCompatExternal(), rendererCompatInternal()};
        for (File f : candidates) {
            if (f == null || !f.isFile()) continue;
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[32];
                int n = in.read(buf);
                if (n <= 0) continue;
                String mode = new String(buf, 0, n, StandardCharsets.UTF_8)
                        .trim().toLowerCase(Locale.US);
                if (mode.equals("full") || mode.equals("legacy")) return mode;
            } catch (Throwable ignored) { }
        }
        return "auto";
    }

    private void setRendererMode(String mode) {
        String normalized = (mode == null) ? "auto" : mode.trim().toLowerCase(Locale.US);
        if (!normalized.equals("auto") && !normalized.equals("full") && !normalized.equals("legacy")) {
            normalized = "auto";
        }

        File[] targets = new File[]{rendererCompatExternal(), rendererCompatInternal()};
        boolean ok = true;
        for (File f : targets) {
            if (f == null) continue;
            try {
                if (normalized.equals("auto")) {
                    if (f.exists() && !f.delete()) ok = false;
                } else {
                    File parent = f.getParentFile();
                    if (parent != null && !parent.isDirectory()) parent.mkdirs();
                    try (FileOutputStream out = new FileOutputStream(f, false)) {
                        out.write((normalized + "\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        try { out.getFD().sync(); } catch (Throwable ignored) { }
                    }
                }
            } catch (Throwable t) {
                ok = false;
            }
        }

        String selected = readRendererMode();
        if (rendererModeText != null) {
            rendererModeText.setText("Next launch: " + selected.toUpperCase(Locale.US)
                    + (ok ? " — fully close and reopen the game" : " — failed to save the setting"));
        }
    }

    private TextView label(String text, float sizeSp, int style) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sizeSp);
        tv.setTypeface(Typeface.DEFAULT, style);
        tv.setTextColor(Color.WHITE);
        tv.setLineSpacing(dp(2), 1.0f);
        tv.setGravity(Gravity.START);
        return tv;
    }

    private View spacer(int heightDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(heightDp)));
        return v;
    }

    private void toastStatus(String msg) {
        if (statusText != null) statusText.setText(msg);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
    }
}
