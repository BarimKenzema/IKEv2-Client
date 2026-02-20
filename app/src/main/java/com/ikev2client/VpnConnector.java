package com.ikev2client;

import android.content.Context;
import android.net.Ikev2VpnProfile;
import android.net.VpnManager;
import android.os.Build;
import android.util.Log;
import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

public class VpnConnector {

    private static final String TAG = "VpnConnector";

    public static boolean connect(Context context, ProfileManager.Profile profile) {
        try {
            X509Certificate caCert = parseCertificate(profile.caCert);

            Ikev2VpnProfile.Builder builder = new Ikev2VpnProfile.Builder(
                profile.server,
                profile.username
            );

            builder.setAuthUsernamePassword(
                profile.username,
                profile.password,
                caCert
            );

            if (profile.dns != null && !profile.dns.isEmpty()) {
                // DNS is handled automatically by the system
            }

            builder.setBypassable(false);
            builder.setMetered(false);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                builder.setRequiresInternetValidation(false);
            }

            Ikev2VpnProfile vpnProfile = builder.build();

            VpnManager vpnManager = (VpnManager) context.getSystemService(Context.VPN_MANAGEMENT_SERVICE);

            if (vpnManager != null) {
                vpnManager.provisionVpnProfile(vpnProfile);
                vpnManager.startProvisionedVpnProfile();
                Log.i(TAG, "VPN started successfully");
                return true;
            } else {
                Log.e(TAG, "VpnManager is null");
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Connection failed: " + e.getMessage(), e);
            return false;
        }
    }

    public static void disconnect(Context context) {
        try {
            VpnManager vpnManager = (VpnManager) context.getSystemService(Context.VPN_MANAGEMENT_SERVICE);
            if (vpnManager != null) {
                vpnManager.stopProvisionedVpnProfile();
                Log.i(TAG, "VPN stopped");
            }
        } catch (Exception e) {
            Log.e(TAG, "Disconnect failed: " + e.getMessage(), e);
        }
    }

    private static X509Certificate parseCertificate(String pemString) throws Exception {
        String cleaned = pemString.trim();
        if (!cleaned.startsWith("-----BEGIN CERTIFICATE-----")) {
            cleaned = "-----BEGIN CERTIFICATE-----\n" + cleaned + "\n-----END CERTIFICATE-----";
        }

        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        ByteArrayInputStream inputStream = new ByteArrayInputStream(cleaned.getBytes("UTF-8"));
        return (X509Certificate) factory.generateCertificate(inputStream);
    }
}
