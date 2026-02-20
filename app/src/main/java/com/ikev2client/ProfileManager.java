package com.ikev2client;

import android.content.Context;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class ProfileManager {
    
    public static class Profile {
        @SerializedName("name")
        public String name;
        
        @SerializedName("server")
        public String server;
        
        @SerializedName("username")
        public String username;
        
        @SerializedName("password")
        public String password;
        
        @SerializedName("ca_cert")
        public String caCert;
        
        @SerializedName("ike_proposal")
        public String ikeProposal;
        
        @SerializedName("esp_proposal")
        public String espProposal;
        
        @SerializedName("dns")
        public String dns;
        
        @SerializedName("expire_date")
        public String expireDate; // Format: "2025-03-20"
        
        public boolean isExpired() {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                Date expiry = sdf.parse(expireDate);
                return new Date().after(expiry);
            } catch (Exception e) {
                return true; // If date parse fails, consider expired
            }
        }
        
        public int daysRemaining() {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                Date expiry = sdf.parse(expireDate);
                long diff = expiry.getTime() - System.currentTimeMillis();
                return (int) (diff / (1000 * 60 * 60 * 24));
            } catch (Exception e) {
                return 0;
            }
        }
    }
    
    public static Profile loadFromFile(File file) throws Exception {
        BufferedReader reader = new BufferedReader(new FileReader(file));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        
        String encrypted = sb.toString();
        String json = Crypto.decrypt(encrypted);
        
        Gson gson = new Gson();
        return gson.fromJson(json, Profile.class);
    }
    
    public static void saveToFile(Profile profile, File file) throws Exception {
        Gson gson = new Gson();
        String json = gson.toJson(profile);
        String encrypted = Crypto.encrypt(json);
        
        FileWriter writer = new FileWriter(file);
        writer.write(encrypted);
        writer.close();
    }
    
    public static List<Profile> loadAllProfiles(Context context) {
        List<Profile> profiles = new ArrayList<>();
        File dir = new File(context.getFilesDir(), "profiles");
        if (!dir.exists()) dir.mkdirs();
        
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().endsWith(".ikev2")) {
                    try {
                        profiles.add(loadFromFile(file));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        return profiles;
    }
}
