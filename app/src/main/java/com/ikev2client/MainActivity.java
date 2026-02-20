package com.ikev2client;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
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
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        listView = findViewById(R.id.profileList);
        Button importBtn = findViewById(R.id.importButton);
        
        loadProfiles();
        
        importBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            startActivityForResult(intent, 100);
        });
        
        listView.setOnItemClickListener((parent, view, position, id) -> {
            ProfileManager.Profile profile = profiles.get(position);
            if (profile.isExpired()) {
                Toast.makeText(this, "This profile has expired!", Toast.LENGTH_SHORT).show();
            } else {
                connectVpn(profile);
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
                
                ProfileManager.Profile profile = ProfileManager.loadFromFile(tempFile);
                
                File profilesDir = new File(getFilesDir(), "profiles");
                if (!profilesDir.exists()) profilesDir.mkdirs();
                
                File savedFile = new File(profilesDir, profile.name + ".ikev2");
                ProfileManager.saveToFile(profile, savedFile);
                
                Toast.makeText(this, "Profile imported successfully!", Toast.LENGTH_SHORT).show();
                loadProfiles();
                
            } catch (Exception e) {
                Toast.makeText(this, "Failed to import: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }
    
    private void loadProfiles() {
        profiles = ProfileManager.loadAllProfiles(this);
        String[] items = new String[profiles.size()];
        for (int i = 0; i < profiles.size(); i++) {
            ProfileManager.Profile p = profiles.get(i);
            items[i] = p.name + " (" + p.daysRemaining() + " days left)";
        }
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, items);
        listView.setAdapter(adapter);
    }
    
    private void connectVpn(ProfileManager.Profile profile) {
        Toast.makeText(this, "Connecting to " + profile.server + "...", Toast.LENGTH_SHORT).show();
        // TODO: Start VPN service with this profile
    }
}
