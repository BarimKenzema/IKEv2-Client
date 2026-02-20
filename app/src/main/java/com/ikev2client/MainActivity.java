package com.ikev2client;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.io.InputStream;
import java.util.List;

public class MainActivity extends AppCompatActivity implements IKEv2VpnService.StateListener {

    private ListView listView;
    private Button importBtn;
    private Button clipboardBtn;
    private Button disconnectBtn;
    private TextView statusText;
    private List<ProfileManager.Profile> profiles;
    private ProfileManager.Profile pendingProfile;

    private static final int VPN_REQUEST_CODE = 200;
    private static final int FILE_REQUEST_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        listView = findViewById(R.id.profileList);
        importBtn = findViewById(R.id.importButton);
        clipboardBtn = findViewById(R.id.clipboardButton);
        disconnectBtn = findViewById(R.id.disconnectButton);
        statusText = findViewById(R.id.statusText);

        IKEv2VpnService.setStateListener(this);
        updateStatus(IKEv2VpnService.getCurrentState());

        loadProfiles();

        importBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(intent, FILE_REQUEST_CODE);
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
                disconnectVpn();
            }
        });

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < profiles.size()) {
                    ProfileManager.Profile profile = profiles.get(position);
                    if (profile.isExpired()) {
                        Toast.makeText(MainActivity.this,
                            "This profile has expired!", Toast.LENGTH_SHORT).show();
                    } else {
                        startVpnConnection(profile);
                    }
                }
            }
        });

        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < profiles.size()) {
                    showProfileDetails(profiles.get(position));
                }
                return true;
            }
        });
    }

    private void startVpnConnection(ProfileManager.Profile profile) {
        pendingProfile = profile;
        Intent vpnIntent = VpnService.prepare(this);
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
        } else {
            connectVpn(profile);
        }
    }

    private void connectVpn(ProfileManager.Profile profile) {
        Intent intent = new Intent(this, IKEv2VpnService.class);
        intent.setAction(IKEv2VpnService.ACTION_CONNECT);
        intent.putExtra(IKEv2VpnService.EXTRA_SERVER, profile.server);
        intent.putExtra(IKEv2VpnService.EXTRA_USERNAME, profile.username);
        intent.putExtra(IKEv2VpnService.EXTRA_PASSWORD, profile.password);
        intent.putExtra(IKEv2VpnService.EXTRA_CA_CERT, profile.caCert);
        intent.putExtra(IKEv2VpnService.EXTRA_PROFILE_NAME, profile.name);
        intent.putExtra(IKEv2VpnService.EXTRA_DNS, profile.dns);
        startService(intent);
    }

    private void disconnectVpn() {
        Intent intent = new Intent(this, IKEv2VpnService.class);
        intent.setAction(IKEv2VpnService.ACTION_DISCONNECT);
        startService(intent);
    }

    @Override
    public void onStateChanged(String state) {
        updateStatus(state);
    }

    private void updateStatus(String state) {
        if (state == null) state = "DISCONNECTED";

        statusText.setText("Status: " + state);

        switch (state) {
            case "CONNECTED":
                statusText.setTextColor(0xFF4CAF50);
                disconnectBtn.setVisibility(View.VISIBLE);
                break;
            case "CONNECTING":
                statusText.setTextColor(0xFFFF9800);
                disconnectBtn.setVisibility(View.VISIBLE);
                break;
            case "DISCONNECTED":
                statusText.setTextColor(0xFF757575);
                disconnectBtn.setVisibility(View.GONE);
                break;
            case "ERROR":
                statusText.setTextColor(0xFFF44336);
                disconnectBtn.setVisibility(View.GONE);
                break;
            default:
                statusText.setTextColor(0xFF757575);
                disconnectBtn.setVisibility(View.GONE);
                break;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && pendingProfile != null) {
                connectVpn(pendingProfile);
            } else {
                Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show();
            }
            pendingProfile = null;
            return;
        }

        if (requestCode == FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            try {
                Uri uri = data.getData();
                InputStream inputStream = getContentResolver().openInputStream(uri);

                StringBuilder sb = new StringBuilder();
                byte[] buffer = new byte[1024];
                int length;
                while ((length = inputStream.read(buffer)) > 0) {
                    sb.append(new String(buffer, 0, length, "UTF-8"));
                }
                inputStream.close();

                importEncryptedProfile(sb.toString().trim());

            } catch (Exception e) {
                Toast.makeText(this,
                    "Failed to read file: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void importFromClipboard() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

            if (clipboard == null || !clipboard.hasPrimaryClip()) {
                Toast.makeText(this, "Clipboard is empty!", Toast.LENGTH_SHORT).show();
                return;
            }

            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                Toast.makeText(this, "Clipboard is empty!", Toast.LENGTH_SHORT).show();
                return;
            }

            CharSequence text = clip.getItemAt(0).getText();
            if (text == null || text.length() == 0) {
                Toast.makeText(this, "Clipboard is empty!", Toast.LENGTH_SHORT).show();
                return;
            }

            importEncryptedProfile(text.toString().trim());

        } catch (Exception e) {
            Toast.makeText(this,
                "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void importEncryptedProfile(String encrypted) {
        try {
            ProfileManager.Profile profile = ProfileManager.loadFromEncryptedString(encrypted);

            if (profile.name == null || profile.name.isEmpty()) {
                Toast.makeText(this, "Invalid profile", Toast.LENGTH_SHORT).show();
                return;
            }

            File profilesDir = new File(getFilesDir(), "profiles");
            if (!profilesDir.exists()) profilesDir.mkdirs();

            File savedFile = new File(profilesDir, profile.name + ".ikev2");
            ProfileManager.saveToFile(profile, savedFile);

            Toast.makeText(this,
                "Profile '" + profile.name + "' imported!", Toast.LENGTH_SHORT).show();

            loadProfiles();

        } catch (Exception e) {
            Toast.makeText(this,
                "Failed to import: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void loadProfiles() {
        profiles = ProfileManager.loadAllProfiles(this);

        if (profiles.size() == 0) {
            String[] empty = new String[]{"No profiles.\nImport a file or paste from clipboard."};
            listView.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, empty));
            return;
        }

        String[] items = new String[profiles.size()];
        for (int i = 0; i < profiles.size(); i++) {
            ProfileManager.Profile p = profiles.get(i);
            String status = p.isExpired() ? "EXPIRED" : p.timeRemaining() + " left";
            items[i] = p.name + "\n" + p.server + " • " + status;
        }

        listView.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_list_item_1, items));
    }

    private void showProfileDetails(ProfileManager.Profile profile) {
        String status = profile.isExpired() ? "EXPIRED" : profile.timeRemaining() + " remaining";

        String details = "Name: " + profile.name + "\n" +
                        "Server: " + profile.server + "\n" +
                        "Username: " + profile.username + "\n" +
                        "Status: " + status + "\n" +
                        "Expires: " + profile.expireDatetime;

        new AlertDialog.Builder(this)
            .setTitle("Profile Details")
            .setMessage(details)
            .setPositiveButton("OK", null)
            .setNegativeButton("Delete", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dialog, int which) {
                    deleteProfile(profile);
                }
            })
            .show();
    }

    private void deleteProfile(ProfileManager.Profile profile) {
        File profilesDir = new File(getFilesDir(), "profiles");
        File file = new File(profilesDir, profile.name + ".ikev2");
        if (file.exists() && file.delete()) {
            Toast.makeText(this, "Profile deleted", Toast.LENGTH_SHORT).show();
            loadProfiles();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IKEv2VpnService.setStateListener(this);
        updateStatus(IKEv2VpnService.getCurrentState());
        loadProfiles();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }
}
