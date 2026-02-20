package com.ikev2client;

import android.content.Intent;
import android.net.VpnService;

public class VpnService extends android.net.VpnService {
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // TODO: Integrate strongSwan VPN connection here
        return START_STICKY;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        // TODO: Disconnect VPN
    }
}
