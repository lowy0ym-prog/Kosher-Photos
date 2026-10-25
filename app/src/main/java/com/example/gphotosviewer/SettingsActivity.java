package com.example.gphotosviewer;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Proxy settings: a switch, the proxy address, the access key, a test button.
 * Open it by long-pressing the app icon and choosing "Proxy settings".
 */
public class SettingsActivity extends Activity {

    private Switch toggle;
    private EditText address;
    private EditText keyBox;
    private TextView status;

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView label(String text, float sizeSp, int topDp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sizeSp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        t.setLayoutParams(lp);
        return t;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ProxyConfig cfg = ProxyConfig.load(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));

        root.addView(label("Stream via proxy", 24f, 0));
        root.addView(label("Turn on if your DNS filter blocks googleusercontent.com. "
                + "Photos then come through your own proxy address instead, "
                + "so the filter can stay on for every other app.", 15f, 8));

        toggle = new Switch(this);
        toggle.setText("Stream via proxy");
        toggle.setTextSize(17f);
        toggle.setChecked(cfg.enabled);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tl.topMargin = dp(20);
        toggle.setLayoutParams(tl);
        root.addView(toggle);

        root.addView(label("Proxy address", 14f, 20));
        address = new EditText(this);
        address.setHint("https://photos-proxy.yourname.workers.dev");
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(cfg.base);
        root.addView(address);

        root.addView(label("Access key", 14f, 14));
        keyBox = new EditText(this);
        keyBox.setHint("The ACCESS_KEY you set in Cloudflare");
        keyBox.setSingleLine(true);
        keyBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyBox.setText(cfg.key);
        root.addView(keyBox);

        Button test = new Button(this);
        test.setText("Test connection");
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(20);
        test.setLayoutParams(bl);
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTest();
            }
        });
        root.addView(test);

        Button save = new Button(this);
        save.setText("Save");
        save.setLayoutParams(bl);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        root.addView(save);

        status = label("", 15f, 16);
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void setStatus(String text) {
        status.setText(text);
    }

    private void runTest() {
        final String base = ProxyConfig.normalizeBase(address.getText().toString());
        final String key = keyBox.getText().toString().trim();
        if (base == null) {
            setStatus("Enter your proxy address, like https://photos-proxy.yourname.workers.dev");
            return;
        }
        if (key.length() < ProxyConfig.MIN_KEY_LENGTH) {
            setStatus("The access key must be at least " + ProxyConfig.MIN_KEY_LENGTH + " characters.");
            return;
        }
        setStatus("Testing...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(base + "/ping").openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                    c.setRequestProperty("X-Access-Key", key);
                    int code = c.getResponseCode();
                    if (code == 200) {
                        msg = "Connected. The proxy works.";
                    } else if (code == 401) {
                        msg = "Reached the proxy, but the access key is wrong.";
                    } else if (code == 500) {
                        msg = "Reached the Worker, but it is not set up: add the ACCESS_KEY secret.";
                    } else {
                        msg = "The proxy answered with an error (" + code + ").";
                    }
                } catch (Exception e) {
                    msg = "Cannot reach that address. Check it and your internet.";
                } finally {
                    if (c != null) c.disconnect();
                }
                final String shown = msg;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setStatus(shown);
                    }
                });
            }
        }).start();
    }

    private void save() {
        boolean on = toggle.isChecked();
        String raw = address.getText().toString().trim();
        String key = keyBox.getText().toString().trim();
        String base = raw.isEmpty() ? "" : ProxyConfig.normalizeBase(raw);
        if (base == null) {
            setStatus("That address does not look right. Use something like "
                    + "https://photos-proxy.yourname.workers.dev");
            return;
        }
        if (on && (base.isEmpty() || key.length() < ProxyConfig.MIN_KEY_LENGTH)) {
            setStatus("To turn the proxy on, enter the address and an access key of at least "
                    + ProxyConfig.MIN_KEY_LENGTH + " characters.");
            return;
        }
        ProxyConfig.save(this, on, base, key);
        Toast.makeText(this, on ? "Proxy on" : "Proxy off", Toast.LENGTH_SHORT).show();
        finish();
    }
}
