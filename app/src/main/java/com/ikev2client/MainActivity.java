package com.ikev2client;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.io.InputStream;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private ListView listView;
    private Button disconnectBtn;
    private TextView statusText;
    private List<ProfileManager.Profile> profiles;
    private Handler handler;
    private Runnable statusChecker;

    private static final int FILE_REQ = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        listView = findViewById(R.id.profileList);
        Button importBtn = findViewById(R.id.importButton);
        Button clipboardBtn = findViewById(R.id.clipboardButton);
        disconnectBtn = findViewById(R.id.disconnectButton);
        statusText = findViewById(R.id.statusText);

        handler = new Handler(Looper.getMainLooper());

        loadProfiles();
        startStatusChecking();

        importBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("*/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(i, FILE_REQ);
            }
        });

        clipboardBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importFromClipboard();
            }
        });

        disconnectBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                VpnConnector.disconnect(MainActivity.this);
                Toast.makeText(MainActivity.this, "Disconnecting...", Toast.LENGTH_SHORT).show();
            }
        });

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < profiles.size()) {
                    ProfileManager.Profile prof = profiles.get(pos);
                    if (prof.isExpired()) {
                        Toast.makeText(MainActivity.this,
                            "This profile has expired!", Toast.LENGTH_SHORT).show();
                    } else {
                        connectVpn(prof);
                    }
                }
            }
        });

        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < profiles.size()) showDetails(profiles.get(pos));
                return true;
            }
        });
    }

    private void connectVpn(ProfileManager.Profile profile) {
        statusText.setText("Status: CONNECTING");
        statusText.setTextColor(0xFFFF9800);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean success = VpnConnector.connect(MainActivity.this, profile);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (success) {
                            Toast.makeText(MainActivity.this,
                                "VPN connection initiated!", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this,
                                "Connection failed!", Toast.LENGTH_SHORT).show();
                            statusText.setText("Status: ERROR");
                            statusText.setTextColor(0xFFF44336);
                        }
                    }
                });
            }
        }).start();
    }

    private void startStatusChecking() {
        statusChecker = new Runnable() {
            @Override
            public void run() {
                checkVpnStatus();
                handler.postDelayed(this, 2000);
            }
        };
        handler.post(statusChecker);
    }

    private void checkVpnStatus() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;

            boolean vpnActive = false;
            Network[] networks = cm.getAllNetworks();
            for (Network network : networks) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpnActive = true;
                    break;
                }
            }

            if (vpnActive) {
                statusText.setText("Status: CONNECTED");
                statusText.setTextColor(0xFF4CAF50);
                disconnectBtn.setVisibility(View.VISIBLE);
            } else {
                String current = statusText.getText().toString();
                if (!current.contains("CONNECTING")) {
                    statusText.setText("Status: DISCONNECTED");
                    statusText.setTextColor(0xFF757575);
                    disconnectBtn.setVisibility(View.GONE);
                }
            }
        } catch (Exception e) {
            // Ignore errors in status checking
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);

        if (req == FILE_REQ && res == Activity.RESULT_OK && data != null) {
            try {
                Uri uri = data.getData();
                InputStream is = getContentResolver().openInputStream(uri);
                StringBuilder sb = new StringBuilder();
                byte[] buf = new byte[1024];
                int len;
                while ((len = is.read(buf)) > 0) {
                    sb.append(new String(buf, 0, len, "UTF-8"));
                }
                is.close();
                importProfile(sb.toString().trim());
            } catch (Exception e) {
                Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void importFromClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                Toast.makeText(this, "Clipboard is empty!", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                Toast.makeText(this, "Clipboard is empty!", Toast.LENGTH_SHORT).show();
                return;
            }
            CharSequence text = clip.getItemAt(0).getText();
            if (text == null || text.length() == 0) {
                Toast.makeText(this, "Clipboard is empty!", Toast.LENGTH_SHORT).show();
                return;
            }
            importProfile(text.toString().trim());
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void importProfile(String encrypted) {
        try {
            ProfileManager.Profile p = ProfileManager.loadFromEncryptedString(encrypted);
            if (p.name == null || p.name.isEmpty()) {
                Toast.makeText(this, "Invalid profile", Toast.LENGTH_SHORT).show();
                return;
            }
            File dir = new File(getFilesDir(), "profiles");
            if (!dir.exists()) dir.mkdirs();
            ProfileManager.saveToFile(p, new File(dir, p.name + ".ikev2"));
            Toast.makeText(this, "Profile '" + p.name + "' imported!", Toast.LENGTH_SHORT).show();
            loadProfiles();
        } catch (Exception e) {
            Toast.makeText(this, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void loadProfiles() {
        profiles = ProfileManager.loadAllProfiles(this);
        if (profiles.size() == 0) {
            listView.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1,
                new String[]{"No profiles.\nImport a file or paste from clipboard."}));
            return;
        }
        String[] items = new String[profiles.size()];
        for (int i = 0; i < profiles.size(); i++) {
            ProfileManager.Profile p = profiles.get(i);
            String st = p.isExpired() ? "EXPIRED" : p.timeRemaining() + " left";
            items[i] = p.name + "\n" + p.server + " - " + st;
        }
        listView.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_list_item_1, items));
    }

    private void showDetails(ProfileManager.Profile p) {
        String st = p.isExpired() ? "EXPIRED" : p.timeRemaining() + " remaining";
        String msg = "Name: " + p.name + "\nServer: " + p.server +
                     "\nUsername: " + p.username + "\nStatus: " + st +
                     "\nExpires: " + p.expireDatetime;
        new AlertDialog.Builder(this)
            .setTitle("Profile Details")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .setNegativeButton("Delete", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int w) {
                    File dir = new File(getFilesDir(), "profiles");
                    File f = new File(dir, p.name + ".ikev2");
                    if (f.exists()) f.delete();
                    Toast.makeText(MainActivity.this, "Deleted", Toast.LENGTH_SHORT).show();
                    loadProfiles();
                }
            })
            .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadProfiles();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handler != null && statusChecker != null) {
            handler.removeCallbacks(statusChecker);
        }
    }
}
