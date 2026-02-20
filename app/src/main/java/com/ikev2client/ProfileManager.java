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

        @SerializedName("expire_datetime")
        public String expireDatetime;

        public boolean isExpired() {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
                Date expiry = sdf.parse(expireDatetime);
                return new Date().after(expiry);
            } catch (Exception e) {
                return true;
            }
        }

        public String timeRemaining() {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
                Date expiry = sdf.parse(expireDatetime);
                long diff = expiry.getTime() - System.currentTimeMillis();
                if (diff < 0) return "Expired";
                long days = diff / (1000 * 60 * 60 * 24);
                long hours = (diff / (1000 * 60 * 60)) % 24;
                long minutes = (diff / (1000 * 60)) % 60;
                if (days > 0) return days + "d " + hours + "h";
                if (hours > 0) return hours + "h " + minutes + "m";
                return minutes + "m";
            } catch (Exception e) {
                return "Unknown";
            }
        }
    }

    public static Profile loadFromEncryptedString(String encrypted) throws Exception {
        String json = Crypto.decrypt(encrypted);
        return new Gson().fromJson(json, Profile.class);
    }

    public static Profile loadFromFile(File file) throws Exception {
        BufferedReader reader = new BufferedReader(new FileReader(file));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        return loadFromEncryptedString(sb.toString());
    }

    public static void saveToFile(Profile profile, File file) throws Exception {
        String json = new Gson().toJson(profile);
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
