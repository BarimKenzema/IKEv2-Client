package com.ikev2client;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.widget.Toast;
import java.io.File;
import java.io.FileWriter;

public class VpnConnector {
    
    private static final String STRONGSWAN_PACKAGE = "org.strongswan.android";
    
    public static void connect(Context context, ProfileManager.Profile profile) {
        if (!isStrongSwanInstalled(context)) {
            Toast.makeText(context, 
                "Please install strongSwan VPN from Play Store", 
                Toast.LENGTH_LONG).show();
            openPlayStore(context);
            return;
        }
        
        try {
            // Create strongSwan-compatible profile file
            File profileFile = createStrongSwanProfile(context, profile);
            
            // Launch strongSwan with this profile
            Intent intent = new Intent();
            intent.setAction(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.fromFile(profileFile), "application/vnd.strongswan.profile");
            intent.setPackage(STRONGSWAN_PACKAGE);
            context.startActivity(intent);
            
        } catch (Exception e) {
            Toast.makeText(context, 
                "Error: " + e.getMessage(), 
                Toast.LENGTH_SHORT).show();
        }
    }
    
    private static boolean isStrongSwanInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(STRONGSWAN_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
    
    private static void openPlayStore(Context context) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, 
                Uri.parse("market://details?id=" + STRONGSWAN_PACKAGE));
            context.startActivity(intent);
        } catch (Exception e) {
            Intent intent = new Intent(Intent.ACTION_VIEW, 
                Uri.parse("https://play.google.com/store/apps/details?id=" + STRONGSWAN_PACKAGE));
            context.startActivity(intent);
        }
    }
    
    private static File createStrongSwanProfile(Context context, ProfileManager.Profile profile) 
            throws Exception {
        
        // strongSwan uses a simple key-value format
        StringBuilder config = new StringBuilder();
        config.append("profile {\n");
        config.append("  name = \"").append(profile.name).append("\"\n");
        config.append("  remote {\n");
        config.append("    addr = \"").append(profile.server).append("\"\n");
        config.append("  }\n");
        config.append("  local {\n");
        config.append("    eap_id = \"").append(profile.username).append("\"\n");
        config.append("    aaa_id = \"").append(profile.username).append("\"\n");
        config.append("  }\n");
        config.append("  ike-proposal = \"").append(profile.ikeProposal).append("\"\n");
        config.append("  esp-proposal = \"").append(profile.espProposal).append("\"\n");
        config.append("}\n");
        
        File cacheDir = context.getCacheDir();
        File profileFile = new File(cacheDir, profile.name + ".sswan");
        
        FileWriter writer = new FileWriter(profileFile);
        writer.write(config.toString());
        writer.close();
        
        // Also save the CA certificate
        File certFile = new File(cacheDir, profile.name + "_ca.pem");
        FileWriter certWriter = new FileWriter(certFile);
        certWriter.write(profile.caCert);
        certWriter.close();
        
        return profileFile;
    }
}
