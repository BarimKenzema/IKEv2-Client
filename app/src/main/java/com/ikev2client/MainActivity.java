package com.ikev2client;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    
    private ListView listView;
    private ArrayAdapter<String> adapter;
    private List<ProfileManager.Profile> profiles;
    private Button importBtn;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        listView = findViewById(R.id.profileList);
        importBtn = findViewById(R.id.importButton);
        
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
                showProfileDetails(profile);
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
                
                File tempFile = new File(getCacheDir(), "temp.ikev2");
                FileOutputStream outputStream = new FileOutputStream(tempFile);
                
                byte[] buffer = new byte[1024];
                int length;
                while ((length = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                }
                outputStream.close();
                inputStream.close();
                
                // Try to load and decrypt the profile
                ProfileManager.Profile profile = ProfileManager.loadFromFile(tempFile);
                
                // Save to profiles directory
                File profilesDir = new File(getFilesDir(), "profiles");
                if (!profilesDir.exists()) {
                    profilesDir.mkdirs();
                }
                
                File savedFile = new File(profilesDir, profile.name + ".ikev2");
                ProfileManager.saveToFile(profile, savedFile);
                
                Toast.makeText(this, 
                    "Profile '" + profile.name + "' imported successfully!", 
                    Toast.LENGTH_SHORT).show();
                
                loadProfiles();
                
            } catch (Exception e) {
                Toast.makeText(this, 
                    "Failed to import profile: " + e.getMessage(), 
                    Toast.LENGTH_LONG).show();
                e.printStackTrace();
            }
        }
    }
    
    private void loadProfiles() {
        profiles = ProfileManager.loadAllProfiles(this);
        
        String[] items = new String[profiles.size()];
        for (int i = 0; i < profiles.size(); i++) {
            ProfileManager.Profile p = profiles.get(i);
            String status = p.isExpired() ? "EXPIRED" : p.timeRemaining();
            items[i] = p.name + "\n" + p.server + " • " + status;
        }
        
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, items);
        listView.setAdapter(adapter);
        
        // Show message if no profiles
        if (profiles.size() == 0) {
            Toast.makeText(this, 
                "No profiles. Tap 'Import .ikev2 Profile' to add one.", 
                Toast.LENGTH_LONG).show();
        }
    }
    
    private void connectVpn(ProfileManager.Profile profile) {
        VpnConnector.connect(this, profile);
    }
    
    private void showProfileDetails(ProfileManager.Profile profile) {
        String details = "Name: " + profile.name + "\n" +
                        "Server: " + profile.server + "\n" +
                        "Username: " + profile.username + "\n" +
                        "Time Remaining: " + profile.timeRemaining() + "\n" +
                        "Status: " + (profile.isExpired() ? "EXPIRED" : "Active");
        
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Profile Details")
            .setMessage(details)
            .setPositiveButton("OK", null)
            .setNegativeButton("Delete", (dialog, which) -> {
                deleteProfile(profile);
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
            Toast.makeText(this, "Failed to delete: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // Refresh the list when returning to the app
        loadProfiles();
    }
}
