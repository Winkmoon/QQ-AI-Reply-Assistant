package com.qqaiassist;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * QQ AI 嘴替设置主页。
 */
public class MainActivity extends Activity {

    private static final String PREF = "config";
    private EditText etKey;
    private EditText etBase;
    private EditText etModel;
    private EditText etPrompt;
    private EditText etChoices;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));

        TextView title = new TextView(this);
        title.setText("QQ AI 嘴替");
        title.setTextSize(22);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(Color.parseColor("#202124"));
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("在 QQ 聊天输入框上方呼出 AI，生成/润色/换语气，一键发送/编辑。");
        desc.setTextSize(13);
        desc.setTextColor(Color.parseColor("#5F6368"));
        desc.setPadding(0, dp(6), 0, dp(16));
        root.addView(desc);

        etKey = addField(root, "API Key（OpenAI 兼容）", "", true);
        etBase = addField(root, "API Base URL", "", false);
        etBase.setHint("例如 https://api.openai.com/v1");
        etModel = addField(root, "模型", "", false);
        etModel.setHint("例如 gpt-4o-mini");
        etPrompt = addField(root, "提示词（告诉 AI 怎么润色）",
                "你是一个擅长把口语/粗糙表达改写成礼貌、清晰、易理解句子的助手。"
                        + "请给出 5 个不同版本，编号返回，不要解释。",
                false, true);
        etChoices = addField(root, "返回条数", "5", false);

        Button save = new Button(this);
        save.setText("保存配置");
        save.setAllCaps(false);
        root.addView(save);

        Button toastInfo = new Button(this);
        toastInfo.setText("使用说明：保存后打开 QQ 聊天页，输入框上方会出现 AI 入口");
        toastInfo.setAllCaps(false);
        toastInfo.setTextColor(Color.parseColor("#0A84FF"));
        toastInfo.setBackgroundColor(Color.TRANSPARENT);
        root.addView(toastInfo);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        loadConfig();
        save.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                saveConfig();
            }
        });
        toastInfo.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                Toast.makeText(MainActivity.this,
                        "保存后打开 QQ 聊天页查看", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private EditText addField(LinearLayout parent, String label, String def,
                              boolean password) {
        return addField(parent, label, def, password, false);
    }

    private EditText addField(LinearLayout parent, String label, String def,
                              boolean password, boolean multiLine) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(14);
        tv.setTextColor(Color.parseColor("#3C4043"));
        tv.setPadding(0, dp(10), 0, dp(2));
        parent.addView(tv);

        EditText et = new EditText(this);
        et.setText(def);
        et.setTextSize(14);
        if (password) {
            et.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        if (multiLine) {
            et.setMinLines(3);
            et.setGravity(Gravity.TOP);
        }
        parent.addView(et);
        return et;
    }

    private void loadConfig() {
        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        etKey.setText(sp.getString("api_key", ""));
        etBase.setText(sp.getString("base_url", ""));
        etModel.setText(sp.getString("model", ""));
        etPrompt.setText(sp.getString("prompt", etPrompt.getText().toString()));
        etChoices.setText(String.valueOf(sp.getInt("choices", 5)));
    }

    private void saveConfig() {
        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        boolean ok = sp.edit()
                .putString("api_key", etKey.getText().toString().trim())
                .putString("base_url", etBase.getText().toString().trim())
                .putString("model", etModel.getText().toString().trim())
                .putString("prompt", etPrompt.getText().toString().trim())
                .putInt("choices", parseChoices())
                .commit();
        try {
            File dataDir = new File(getApplicationInfo().dataDir);
            File prefsDir = new File(dataDir, "shared_prefs");
            File f = new File(prefsDir, "config.xml");
            // 让 QQ 进程里的 XSharedPreferences 能读到配置
            dataDir.setExecutable(true, false);
            prefsDir.setExecutable(true, false);
            f.setReadable(true, false);
            f.setWritable(true, false);
        } catch (Throwable ignored) {
        }
        syncToQqFiles();
        Toast.makeText(this, ok ? "配置已保存并尝试同步" : "保存失败", Toast.LENGTH_SHORT).show();
    }

    private void syncToQqFiles() {
        try {
            String content = "api_key=" + etKey.getText().toString().trim() + "\n"
                    + "base_url=" + etBase.getText().toString().trim() + "\n"
                    + "model=" + etModel.getText().toString().trim() + "\n"
                    + "prompt=" + etPrompt.getText().toString().trim() + "\n"
                    + "choices=" + parseChoices() + "\n";
            File tmp = new File(getCacheDir(), "qqaiassist_config.txt");
            FileOutputStream fos = new FileOutputStream(tmp);
            fos.write(content.getBytes("UTF-8"));
            fos.flush();
            fos.close();
            String cmd = "for p in $(pidof com.tencent.mobileqq); do "
                    + "for u in 0 999; do "
                    + "d=/proc/$p/root/data/user/$u/com.tencent.mobileqq/files; "
                    + "[ -d \"$d\" ] && cp " + tmp.getAbsolutePath()
                    + " $d/qqaiassist_config.txt && "
                    + "chown $(stat -c %u $d):$(stat -c %g $d) "
                    + "$d/qqaiassist_config.txt && chmod 660 $d/qqaiassist_config.txt; "
                    + "done; done";
            String out = runRoot(cmd);
            if (!out.contains("No such") && out.length() < 200) {
                // 静默成功
            }
        } catch (Throwable t) {
            // 跨 App 写 QQ 私有目录常被系统限制，不打扰用户；
            // 配置仍已保存到模块，后续可通过更稳的通道同步。
        }
    }

    private String runRoot(String cmd) throws Exception {
        String[] su = {"su", "/system/bin/su", "/data/adb/ksu/bin/su"};
        Process p = null;
        Throwable last = null;
        for (String s : su) {
            try {
                p = Runtime.getRuntime().exec(new String[]{s, "-c", cmd});
                break;
            } catch (Throwable t) {
                last = t;
            }
        }
        if (p == null) {
            throw new IllegalStateException("找不到 su: " + last);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream is = p.getInputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = is.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        p.waitFor();
        return out.toString("UTF-8");
    }

    private int parseChoices() {
        try {
            int n = Integer.parseInt(etChoices.getText().toString().trim());
            return Math.max(1, Math.min(10, n));
        } catch (Throwable t) {
            return 5;
        }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
