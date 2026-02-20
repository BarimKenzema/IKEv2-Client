package com.ikev2client;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    
    private ListView listView;
    private ArrayAdapter<String> adapter;
    private List<ProfileManager.Profile> profiles;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        listView = findViewById(R.id.profileList);
        Button importBtn = findViewById(R.id.importButton);
        Button clipboardBtn = findViewById(R.id.clipboardButton);
        
        loadProfiles();
        
        importBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(intent, 100);
            }
        });
        
        clipboardBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importFromClipboard();
            }
        });
        
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                ProfileManager.Profile profile = profiles.get(position);
                
                if (profile.isExpired()) {
                    Toast.makeText(MainActivity.this, 
                        "This profile has expired!", 
                        Toast.LENGTH_SHORT).show();
                } else {
                    connectVpn(profile);
                }
            }
        });
        
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                ProfileManager.Profile profile = profiles.get(position);
                showProfileDetails(profile, position);
                return true;
            }
        });
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == 100 && resultCode == Activity.RESULT_OK && data != null) {
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
                
                String encrypted = sb.toString().trim();
                importEncryptedProfile(encrypted);
                
            } catch (Exception e) {
                Toast.makeText(this, 
                    "Failed to read file: " + e.getMessage(), 
                    Toast.LENGTH_LONG).show();
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
            
            String encrypted = clip.getItemAt(0).getText().toString().trim();
            
            if (encrypted.isEmpty()) {
                Toast.makeText(this, "Clipboard is empty!", Toast.LENGTH_SHORT).show();
                return;
            }
            
            importEncryptedProfile(encrypted);
            
        } catch (Exception e) {
            Toast.makeText(this, 
                "Failed to import from clipboard: " + e.getMessage(), 
                Toast.LENGTH_LONG).show();
        }
    }
    
    private void importEncryptedProfile(String encrypted) {
        try {
            String json = Crypto.decrypt(encrypted);
            
            com.google.gson.Gson gson = new com.google.gson.Gson();
            ProfileManager.Profile profile = gson.fromJson(json, ProfileManager.Profile.class);
            
            if (profile.name == null || profile.name.isEmpty()) {
                Toast.makeText(this, "Invalid profile: no name found", Toast.LENGTH_SHORT).show();
                return;
            }
            
            File profilesDir = new File(getFilesDir(), "profiles");
            if (!profilesDir.exists()) {
                profilesDir.mkdirs();
            }
            
            File savedFile = new File(profilesDir, profile.name + ".ikev2");
            ProfileManager.saveToFile(profile, savedFile);
            
            Toast.makeText(this, 
                "Profile '" + profile.name + "' imported!", 
                Toast.LENGTH_SHORT).show();
            
            loadProfiles();
            
        } catch (Exception e) {
            Toast.makeText(this, 
                "Failed to import: " + e.getMessage(), 
                Toast.LENGTH_LONG).show();
        }
    }
    
    private void loadProfiles() {
        profiles = ProfileManager.loadAllProfiles(this);
        
        if (profiles.size() == 0) {
            String[] empty = new String[]{"No profiles yet.\nImport a .ikev2 file or paste from clipboard."};
            adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, empty);
            listView.setAdapter(adapter);
            return;
        }
        
        String[] items = new String[profiles.size()];
        for (int i = 0; i < profiles.size(); i++) {
            ProfileManager.Profile p = profiles.get(i);
            String status;
            if (p.isExpired()) {
                status = "EXPIRED";
            } else {
                status = p.timeRemaining() + " remaining";
            }
            items[i] = p.name + "\n" + p.server + " • " + status;
        }
        
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, items);
        listView.setAdapter(adapter);
    }
    
    private void connectVpn(ProfileManager.Profile profile) {
        VpnConnector.connect(this, profile);
    }
    
    private void showProfileDetails(ProfileManager.Profile profile, int position) {
        String status;
        if (profile.isExpired()) {
            status = "EXPIRED";
        } else {
            status = profile.timeRemaining() + " remaining";
        }
        
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
        try {
            File profilesDir = new File(getFilesDir(), "profiles");
            File file = new File(profilesDir, profile.name + ".ikev2");
            
            if (file.exists() && file.delete()) {
                Toast.makeText(this, "Profile deleted", Toast.LENGTH_SHORT).show();
                loadProfiles();
            }
        } catch (Exception e) {
            Toast.makeText(this, 
                "Failed to delete: " + e.getMessage(), 
                Toast.LENGTH_SHORT).show();
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        loadProfiles();
    }
}
