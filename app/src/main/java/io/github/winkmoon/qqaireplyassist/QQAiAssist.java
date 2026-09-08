package io.github.winkmoon.qqaireplyassist;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * QQ AI 嘴替 Xposed 主逻辑。
 */
public class QQAiAssist implements IXposedHookLoadPackage {

    private static final String TAG = "QQAiAssist";
    private static final String PREF_PKG = "io.github.winkmoon.qqaireplyassist";
    private static final String PREF = "config";

    private static volatile boolean sEntryShown;
    private static volatile boolean sDiscoveryShown;
    private static volatile ClassLoader sClassLoader;
    private static volatile PopupWindow sPopup;
    private static volatile Activity sPopupActivity;

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) {
        if (!"com.tencent.mobileqq".equals(lpparam.packageName)
                || !"com.tencent.mobileqq".equals(lpparam.processName)) {
            return;
        }
        sClassLoader = lpparam.classLoader;
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                final Activity activity = (Activity) param.thisObject;
                                // 只有切到另一个 Activity 才重置；同一聊天页 onResume 不重建
                                if (sPopupActivity != activity) {
                                    if (sPopup != null) {
                                        try {
                                            sPopup.dismiss();
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                    sPopup = null;
                                    sPopupActivity = activity;
                                    sEntryShown = false;
                                }
                                ensureAiEntry(activity);
                                // 多次延迟检查：群聊可能在同一个 Activity 内切换，
                                // onResume 不会再触发，等 send_btn/输入框出现后再显示
                                final View decor = activity.getWindow() != null
                                        ? activity.getWindow().getDecorView() : null;
                                if (decor != null) {
                                    scheduleEntryRetry(activity, decor, 800L);
                                    scheduleEntryRetry(activity, decor, 1800L);
                                    scheduleEntryRetry(activity, decor, 3500L);
                                    scheduleEntryRetry(activity, decor, 6000L);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " hook error: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + " chat activity hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " init failed: " + t);
        }
    }

    private static void scheduleEntryRetry(final Activity activity,
                                           final View decor,
                                           final long delay) {
        decor.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!sEntryShown && !activity.isFinishing()) {
                        ensureAiEntry(activity);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " retry error: " + t);
                }
            }
        }, delay);
    }

    private static void createAiEntry(final Activity activity) {
        if (sEntryShown || activity.isFinishing()) {
            return;
        }
        final EditText input = findInputEditText(activity);
        if (input == null) {
            XposedBridge.log(TAG + " no QQ input found in "
                    + activity.getClass().getName());
            return;
        }
        String hint = input.getHint() == null ? "" : input.getHint().toString();
        XposedBridge.log(TAG + " input selected id=" + input.getId()
                + " hint=" + hint + " text=" + input.getText());
        // 必须有 QQ 发送按钮才算聊天页，避免跑到主页搜索框
        Button sendProbe = findSendButton(activity);
        if (sendProbe == null) {
            XposedBridge.log(TAG + " send_btn not found, skip non-chat page");
            return;
        }
        sEntryShown = true;

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        int p = dp(activity, 4);
        box.setPadding(p, p, p, p);

        Button btn = new Button(activity);
        btn.setText("AI");
        btn.setTextSize(16);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(Color.WHITE);
        android.graphics.drawable.GradientDrawable circle =
                new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(Color.parseColor("#0A84FF"));
        btn.setBackground(circle);
        box.addView(btn);

        int size = dp(activity, 52);
        final PopupWindow pw = new PopupWindow(box, size, size, false);
        pw.setOutsideTouchable(false);
        try {
            pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    Color.TRANSPARENT));
        } catch (Throwable ignored) {
        }
        sPopup = pw;
        pw.setOnDismissListener(new PopupWindow.OnDismissListener() {
            @Override
            public void onDismiss() {
                if (sPopup == pw) {
                    sPopup = null;
                    sEntryShown = false;
                }
            }
        });

        final View decor = activity.getWindow().getDecorView();
        final int[] winPos = {dp(activity, 12), dp(activity, 12)};
        final int touchSlop = android.view.ViewConfiguration.get(activity)
                .getScaledTouchSlop();
        final boolean[] dragged = {false};

        btn.setOnTouchListener(new View.OnTouchListener() {
            private float startRawX;
            private float startRawY;
            private int startWinX;
            private int startWinY;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        dragged[0] = false;
                        startRawX = event.getRawX();
                        startRawY = event.getRawY();
                        startWinX = winPos[0];
                        startWinY = winPos[1];
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - startRawX;
                        float dy = event.getRawY() - startRawY;
                        if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                            dragged[0] = true;
                        }
                        if (dragged[0]) {
                            winPos[0] = startWinX + (int) dx;
                            winPos[1] = startWinY + (int) dy;
                            try {
                                pw.update(winPos[0], winPos[1], -1, -1);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " drag update failed: " + t);
                            }
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        if (!dragged[0]) {
                            btn.performClick();
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });

        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 不 dismiss，弹窗回来后入口仍在
                openRephraseDialog(activity, input);
            }
        });

        decor.post(new Runnable() {
            @Override
            public void run() {
                try {
                    int dw = decor.getWidth();
                    int dh = decor.getHeight();
                    if (dw > 0) {
                        winPos[0] = dw - size - dp(activity, 12);
                    }
                    if (dh > 0) {
                        winPos[1] = dp(activity, 12);
                    }
                    pw.showAtLocation(decor, Gravity.TOP | Gravity.LEFT,
                            winPos[0], winPos[1]);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " popup show failed: " + t);
                }
            }
        });
    }

    private static void ensureAiEntry(final Activity activity) {
        if (sEntryShown || sDiscoveryShown || activity.isFinishing()) {
            return;
        }
        final EditText input = findInputEditText(activity);
        final Button sendProbe = findSendButton(activity);
        if (input == null || sendProbe == null) {
            return;
        }
        String version = resolveQQVersion(activity);
        if (version == null) {
            version = "unknown";
        }
        if (hookCacheHit(activity, version)) {
            createAiEntry(activity);
            return;
        }
        sDiscoveryShown = true;
        showHookDiscovery(activity, version, input, sendProbe);
    }

    private static void showHookDiscovery(final Activity activity,
                                          final String version,
                                          final EditText input,
                                          final Button sendProbe) {
        final LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 20);
        box.setPadding(pad, dp(activity, 8), pad, 0);

        final ProgressBar bar = new ProgressBar(activity, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(
                Color.parseColor("#0A84FF")));
        bar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        Color.parseColor("#E3E6E8")));
        box.addView(bar);

        final TextView tv = new TextView(activity);
        tv.setTextSize(14);
        tv.setTextColor(Color.parseColor("#202124"));
        tv.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        tv.setGravity(Gravity.START);
        tv.setPadding(0, dp(activity, 12), 0, 0);
        tv.setText("正在查找 hook 点...");
        box.addView(tv);

        final AlertDialog dialog = new AlertDialog.Builder(activity,
                android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("Xinran正在查找hook点ing…")
                .setCancelable(false)
                .setView(box)
                .create();
        dialog.show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    update(bar, tv, 10,
                            "正在查找 QQ 版本类：\ncom.tencent.common.config.AppSetting");
                    String v = resolveQQVersion(activity);
                    if (v == null) {
                        v = version;
                    }
                    update(bar, tv, 25, "查找到版本号：\nQQ " + v);

                    update(bar, tv, 40,
                            "正在查找聊天输入框：\ncom.tencent.mobileqq:id/input");
                    if (input == null) {
                        throw new IllegalStateException("找不到聊天输入框");
                    }
                    update(bar, tv, 55, "找到输入框：\nresourceId=input");

                    update(bar, tv, 70,
                            "正在查找发送按钮：\ncom.tencent.mobileqq:id/send_btn");
                    if (sendProbe == null) {
                        throw new IllegalStateException("找不到发送按钮");
                    }
                    update(bar, tv, 85, "找到发送按钮：\nresourceId=send_btn");

                    writeHookCache(activity, v);
                    update(bar, tv, 95, "正在注入 AI 悬浮入口...");
                    update(bar, tv, 100, "注入成功：\n已找到输入框 + 发送按钮");

                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                dialog.dismiss();
                            } catch (Throwable ignored) {
                            }
                            sDiscoveryShown = false;
                            createAiEntry(activity);
                        }
                    }, 3000L);
                } catch (final Throwable t) {
                    XposedBridge.log(TAG + " discovery failed: " + t);
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                dialog.dismiss();
                            } catch (Throwable ignored) {
                            }
                            sDiscoveryShown = false;
                        }
                    }, 1000L);
                }
            }
        }).start();
    }

    private static void update(final ProgressBar bar, final TextView tv,
                               final int progress, final String msg) {
        postToMain(new Runnable() {
            @Override
            public void run() {
                bar.setProgress(progress);
                tv.setText("进度 " + progress + "%\n" + msg);
                XposedBridge.log(TAG + " [" + progress + "%] " + msg);
            }
        });
        try {
            Thread.sleep(320L);
        } catch (InterruptedException ignored) {
        }
    }

    private static File hookCacheFile(Activity activity) {
        return new File(activity.getFilesDir(), "qqaireplyassist_hook_cache.txt");
    }

    private static boolean hookCacheHit(Activity activity, String version) {
        try {
            File f = hookCacheFile(activity);
            if (!f.exists()) {
                return false;
            }
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line;
            boolean ok = false;
            while ((line = br.readLine()) != null) {
                if (line.trim().equals("version=" + version)) {
                    ok = true;
                }
            }
            br.close();
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void writeHookCache(Activity activity, String version) {
        try {
            File f = hookCacheFile(activity);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(("version=" + version + "\n").getBytes("UTF-8"));
            fos.flush();
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    private static String resolveQQVersion(Activity activity) {
        try {
            ClassLoader cl = sClassLoader;
            if (cl == null) {
                cl = activity.getClassLoader();
            }
            Class<?> appSetting = XposedHelpers.findClass(
                    "com.tencent.common.config.AppSetting", cl);
            Pattern p = Pattern.compile("\\d+\\.\\d+\\.\\d+");
            for (java.lang.reflect.Field f : appSetting.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if (val instanceof String) {
                        Matcher m = p.matcher((String) val);
                        if (m.find()) {
                            return m.group();
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void openRephraseDialog(final Activity activity,
                                           final EditText qqInput) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(activity, 24), dp(activity, 8),
                dp(activity, 24), 0);

        final EditText et = new EditText(activity);
        et.setHint("输入你想说的话，例如：帮我跟他说我不去了");
        et.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setMinLines(3);
        et.setGravity(Gravity.TOP);
        box.addView(et);

        final AlertDialog dlg = new AlertDialog.Builder(activity,
                android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("AI 提词")
                .setView(box)
                .setCancelable(true)
                .setPositiveButton("AI 润色", null)
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        dlg.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String raw = et.getText().toString().trim();
                        if (raw.length() == 0) {
                            toast(activity, "先输入想说的话");
                            return;
                        }
                        dlg.dismiss();
                        callAi(activity, qqInput, raw);
                    }
                });
    }

    private static void callAi(final Activity activity,
                               final EditText qqInput,
                               final String raw) {
        final Config cfg = loadConfig(activity);
        if (cfg == null || cfg.apiKey.length() == 0) {
            showQQSetupDialog(activity, qqInput, raw);
            return;
        }

        final AlertDialog wait = new AlertDialog.Builder(activity,
                android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("AI 嘴替")
                .setMessage("正在生成回复...")
                .setCancelable(false)
                .create();
        wait.show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<String> results = aiRephrase(cfg, raw);
                    postToMain(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                wait.dismiss();
                            } catch (Throwable ignored) {
                            }
                            if (results.isEmpty()) {
                                toast(activity, "AI 没有返回可用结果");
                                return;
                            }
                            showResultsDialog(activity, qqInput, results);
                        }
                    });
                } catch (final Throwable t) {
                    postToMain(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                wait.dismiss();
                            } catch (Throwable ignored) {
                            }
                            toast(activity, "AI 请求失败: " + t.getMessage());
                            XposedBridge.log(TAG + " request failed: " + t);
                        }
                    });
                }
            }
        }).start();
    }

    private static void showQQSetupDialog(final Activity activity,
                                          final EditText qqInput,
                                          final String raw) {
        final LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(activity, 24), dp(activity, 4),
                dp(activity, 24), 0);

        final EditText etKey = new EditText(activity);
        etKey.setHint("API Key（OpenAI 兼容）");
        etKey.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(etKey);

        final EditText etBase = new EditText(activity);
        etBase.setHint("API Base URL，例如 https://api.openai.com/v1");
        box.addView(etBase);

        final EditText etModel = new EditText(activity);
        etModel.setHint("模型，例如 gpt-4o-mini");
        box.addView(etModel);

        final EditText etPrompt = new EditText(activity);
        etPrompt.setHint("提示词（可选）");
        etPrompt.setMinLines(3);
        etPrompt.setGravity(Gravity.TOP);
        box.addView(etPrompt);

        final AlertDialog dlg = new AlertDialog.Builder(activity,
                android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("QQ 内配置 AI")
                .setMessage("模块主页配置暂时无法跨 App 读取，请在这里填一次，QQ 会保存到自己的目录。")
                .setView(box)
                .setPositiveButton("保存并重试", null)
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        dlg.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String key = etKey.getText().toString().trim();
                        String base = etBase.getText().toString().trim();
                        String model = etModel.getText().toString().trim();
                        if (key.length() == 0) {
                            toast(activity, "API Key 不能为空");
                            return;
                        }
                        if (base.length() == 0 || model.length() == 0) {
                            toast(activity, "API Base URL 和模型不能为空");
                            return;
                        }
                        try {
                            writeQqConfigFile(activity, key, base, model,
                                    etPrompt.getText().toString().trim(), 5);
                            dlg.dismiss();
                            toast(activity, "配置已写入 QQ 目录");
                            callAi(activity, qqInput, raw);
                        } catch (Throwable t) {
                            toast(activity, "写入失败: " + t.getMessage());
                        }
                    }
                });
    }

    private static void writeQqConfigFile(Activity activity, String key,
                                          String base, String model,
                                          String prompt, int choices)
            throws Exception {
        File f = new File(activity.getFilesDir(), "qqaireplyassist_config.txt");
        FileOutputStream fos = new FileOutputStream(f);
        String content = "api_key=" + key + "\n"
                + "base_url=" + base + "\n"
                + "model=" + model + "\n"
                + "prompt=" + prompt + "\n"
                + "choices=" + choices + "\n";
        fos.write(content.getBytes("UTF-8"));
        fos.flush();
        fos.close();
        XposedBridge.log(TAG + " config written to QQ files: " + f.getAbsolutePath());
    }

    private static void showResultsDialog(final Activity activity,
                                          final EditText qqInput,
                                          final List<String> results) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 24);
        box.setPadding(pad, dp(activity, 8), pad, 0);

        TextView title = new TextView(activity);
        title.setText("选择一条（点击后可发送/编辑）");
        title.setTextSize(14);
        title.setTextColor(Color.parseColor("#5F6368"));
        box.addView(title);

        for (int i = 0; i < results.size(); i++) {
            final String text = results.get(i);
            Button b = new Button(activity);
            b.setAllCaps(false);
            b.setGravity(Gravity.START);
            b.setText((i + 1) + ". " + text);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showSendEditDialog(activity, qqInput, text);
                }
            });
            box.addView(b);
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(box);
        new AlertDialog.Builder(activity,
                android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("AI 润色结果")
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .show();
    }

    private static void showSendEditDialog(final Activity activity,
                                           final EditText qqInput,
                                           final String text) {
        new AlertDialog.Builder(activity,
                android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("发送这条？")
                .setMessage(text)
                .setPositiveButton("发送", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        EditText fresh = findInputEditText(activity);
                        if (fresh == null) {
                            fresh = qqInput;
                        }
                        sendText(activity, fresh, text);
                    }
                })
                .setNeutralButton("编辑", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        EditText fresh = findInputEditText(activity);
                        if (fresh == null) {
                            fresh = qqInput;
                        }
                        XposedBridge.log(TAG + " edit clicked, input id="
                                + fresh.getId() + " text=" + text);
                        pasteIntoInput(activity, fresh, text, null);
                        toast(activity, "已填入 QQ 输入框，可自行修改/发送");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void sendText(Activity activity, EditText input, String text) {
        XposedBridge.log(TAG + " send clicked, input id=" + input.getId()
                + " text=" + text);
        pasteIntoInput(activity, input, text, new Runnable() {
            @Override
            public void run() {
                Button send = findSendButton(activity);
                XposedBridge.log(TAG + " send lookup found=" + (send != null)
                        + " enabled=" + (send != null && send.isEnabled()));
                if (send != null && send.isEnabled()) {
                    send.performClick();
                    toast(activity, "已发送");
                } else {
                    input.requestFocus();
                    toast(activity, "未能点击发送，文字已填入，请手动发送");
                }
            }
        });
    }

    private static void pasteIntoInput(final Activity activity,
                                       final EditText input,
                                       final String text,
                                       final Runnable afterPaste) {
        try {
            ClipboardManager cm = (ClipboardManager) activity
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("QQAiAssist", text));
            input.requestFocus();
            // 先让 QQ 拿到焦点，再走系统粘贴路径，触发 QQ 自己的输入监听
            input.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        input.onTextContextMenuItem(android.R.id.paste);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " paste failed, fallback setText: " + t);
                        input.setText(text);
                        input.setSelection(input.length());
                    }
                    if (afterPaste != null) {
                        input.postDelayed(afterPaste, 250L);
                    }
                }
            }, 60L);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " clipboard failed, fallback setText: " + t);
            input.setText(text);
            input.setSelection(input.length());
            if (afterPaste != null) {
                input.postDelayed(afterPaste, 250L);
            }
        }
    }

    // ------------------------------------------------------------------
    // View 搜索
    // ------------------------------------------------------------------

    private static EditText findInputEditText(Activity activity) {
        try {
            Window w = activity.getWindow();
            if (w == null) {
                return null;
            }
            View decor = w.getDecorView();
            int qqInputId = 0;
            try {
                qqInputId = activity.getResources().getIdentifier(
                        "input", "id", "com.tencent.mobileqq");
            } catch (Throwable ignored) {
            }
            final List<EditText> all = new ArrayList<EditText>();
            collectEditTexts(decor, all);
            EditText best = null;
            int bestScore = -1;
            for (EditText et : all) {
                int score = 0;
                if (qqInputId != 0 && et.getId() == qqInputId) {
                    score += 1000; // 精确命中 QQ 聊天输入框
                }
                if (et.isShown()) {
                    score += 10;
                }
                if (et.isFocused()) {
                    score += 30;
                }
                String hint = et.getHint() == null ? "" : et.getHint().toString();
                if (hint.contains("发送") || hint.contains("消息")
                        || hint.contains("输入") || hint.contains("回复")
                        || hint.contains("说点什么") || hint.contains("留言")) {
                    score += 50;
                }
                if (et.getInputType() != 0) {
                    score += 5;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = et;
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void collectEditTexts(View v, List<EditText> out) {
        if (v instanceof EditText) {
            out.add((EditText) v);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectEditTexts(g.getChildAt(i), out);
            }
        }
    }

    private static Button findSendButton(Activity activity) {
        try {
            int sendId = activity.getResources().getIdentifier(
                    "send_btn", "id", "com.tencent.mobileqq");
            View decor = activity.getWindow().getDecorView();
            return findSendButton(decor, sendId);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Button findSendButton(View v, int sendId) {
        if (v instanceof Button) {
            Button b = (Button) v;
            String t = b.getText() == null ? "" : b.getText().toString();
            boolean byText = "发送".equals(t.trim())
                    || "send".equalsIgnoreCase(t.trim());
            boolean byId = sendId != 0 && b.getId() == sendId;
            if (byText || byId) {
                return b;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                Button b = findSendButton(g.getChildAt(i), sendId);
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    private static Config loadConfig(Context ctx) {
        try {
            // 优先读 QQ 自己 files 目录下的配置（最可靠，不依赖跨 App 权限）
            File f = new File(ctx.getFilesDir(), "qqaireplyassist_config.txt");
            if (f.exists()) {
                Config c = readConfigFile(f);
                if (c != null && c.apiKey.length() > 0
                        && c.baseUrl.length() > 0 && c.model.length() > 0) {
                    XposedBridge.log(TAG + " config loaded from QQ files");
                    return c;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " loadConfig from QQ files failed: " + t);
        }
        try {
            XSharedPreferences sp = new XSharedPreferences(PREF_PKG, PREF);
            sp.reload();
            Config c = new Config();
            c.apiKey = sp.getString("api_key", "");
            c.baseUrl = sp.getString("base_url", "");
            c.model = sp.getString("model", "");
            c.prompt = sp.getString("prompt", "");
            c.choices = sp.getInt("choices", 5);
            if (c.apiKey.length() == 0
                    || c.baseUrl.length() == 0 || c.model.length() == 0) {
                return null;
            }
            return c;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " loadConfig failed: " + t);
            return null;
        }
    }

    private static Config readConfigFile(File f) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            Config c = new Config();
            c.baseUrl = "";
            c.model = "";
            c.choices = 5;
            String line;
            while ((line = br.readLine()) != null) {
                int idx = line.indexOf('=');
                if (idx <= 0) {
                    continue;
                }
                String k = line.substring(0, idx).trim();
                String v = line.substring(idx + 1).trim();
                if ("api_key".equals(k)) {
                    c.apiKey = v;
                } else if ("base_url".equals(k)) {
                    c.baseUrl = v;
                } else if ("model".equals(k)) {
                    c.model = v;
                } else if ("prompt".equals(k)) {
                    c.prompt = v;
                } else if ("choices".equals(k)) {
                    try {
                        c.choices = Integer.parseInt(v);
                    } catch (Throwable ignored) {
                    }
                }
            }
            br.close();
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    private static class Config {
        String apiKey;
        String baseUrl;
        String model;
        String prompt;
        int choices;
    }

    // ------------------------------------------------------------------
    // OpenAI 兼容 Chat Completions API
    // ------------------------------------------------------------------

    private static List<String> aiRephrase(Config cfg, String raw)
            throws Exception {
        String url = cfg.baseUrl;
        if (!url.endsWith("/chat/completions")) {
            if (url.endsWith("/")) {
                url = url + "chat/completions";
            } else {
                url = url + "/chat/completions";
            }
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + cfg.apiKey);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setDoOutput(true);

        JSONObject body = new JSONObject();
        body.put("model", cfg.model);
        body.put("temperature", 0.7);
        body.put("max_tokens", 1024);

        String prompt = cfg.prompt != null && cfg.prompt.length() > 0
                ? cfg.prompt : "请润色为5个礼貌清晰的版本，编号返回";
        if (!prompt.contains("%s") && !prompt.contains("{input}")) {
            prompt = prompt + "\n\n用户原话：\n" + raw;
        } else {
            prompt = prompt.replace("%s", raw).replace("{input}", raw);
        }

        JSONArray messages = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", "你是中文表达润色助手，只输出润色结果，不要解释。");
        messages.put(sys);
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", prompt);
        messages.put(user);
        body.put("messages", messages);
        body.put("stream", false);

        OutputStream os = conn.getOutputStream();
        os.write(body.toString().getBytes("UTF-8"));
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        InputStream is = code >= 200 && code < 300
                ? conn.getInputStream() : conn.getErrorStream();
        String resp = readAll(is);
        is.close();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + ": " + resp);
        }

        JSONObject obj = new JSONObject(resp);
        JSONArray choices = obj.getJSONArray("choices");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < choices.length(); i++) {
            JSONObject ch = choices.getJSONObject(i);
            String msg = ch.getJSONObject("message").optString("content", "");
            sb.append(msg);
        }
        return splitLines(sb.toString(), cfg.choices);
    }

    private static List<String> splitLines(String text, int max) {
        List<String> out = new ArrayList<String>();
        if (text == null || text.length() == 0) {
            return out;
        }
        String[] lines = text.split("\n");
        StringBuilder cur = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.length() == 0) {
                continue;
            }
            // 去掉 "1." "1、" "1）" 等编号前缀
            if (trimmed.matches("^\\s*\\d+[.、)）:：].*")) {
                if (cur.length() > 0) {
                    out.add(cur.toString().trim());
                }
                cur = new StringBuilder(trimmed.replaceFirst(
                        "^\\s*\\d+[.、)）:：]\\s*", ""));
            } else {
                if (cur.length() > 0) {
                    cur.append("\n");
                }
                cur.append(trimmed);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString().trim());
        }
        if (out.size() > max) {
            return out.subList(0, max);
        }
        return out;
    }

    private static String readAll(InputStream in) throws Exception {
        BufferedReader br = new BufferedReader(
                new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        br.close();
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // UI helpers
    // ------------------------------------------------------------------

    private static void postToMain(Runnable r) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                r.run();
            } else {
                new Handler(Looper.getMainLooper()).post(r);
            }
        } catch (Throwable t) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void toast(final Context ctx, final String msg) {
        postToMain(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static int dp(Context ctx, int v) {
        return Math.round(ctx.getResources().getDisplayMetrics().density * v);
    }
}
