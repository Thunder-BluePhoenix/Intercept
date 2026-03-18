package com.example.intercept

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class CallAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Minimal implementation. The OS-level "microphone mute bypass" 
        // is granted by the system simply because this service is active and enabled
        // by the user in Settings > Accessibility.
    }

    override fun onInterrupt() {
        Log.d("CallAccessibility", "onInterrupt")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("CallAccessibility", "Accessibility service connected")
        sendBroadcast(Intent("com.example.intercept.ACCESSIBILITY_ENABLED"))
    }
}
