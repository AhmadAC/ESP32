// app/src/main/java/com/example/mybasicapp/GamepadAccessibilityService.kt
package com.example.mybasicapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class GamepadAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GamepadAccessibility"
        var isServiceEnabled = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceEnabled = true
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        serviceInfo = info
        Log.i(TAG, "GamepadAccessibilityService Connected - Global Key Listening Active!")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val isGamepad = (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                        (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

        if (isGamepad && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                // Switch Pro B Button (Close Claw / Stop Robot)
                KeyEvent.KEYCODE_BUTTON_B -> {
                    dispatchBleCommand("claw:close")
                    dispatchBleCommand("action:stop")
                    return true
                }
                // Switch Pro A Button (Open Claw / Stand Robot)
                KeyEvent.KEYCODE_BUTTON_A -> {
                    dispatchBleCommand("claw:open")
                    dispatchBleCommand("action:stand")
                    return true
                }
                // Switch Pro Y Button (Half Open Claw / Sit Robot)
                KeyEvent.KEYCODE_BUTTON_Y -> {
                    dispatchBleCommand("claw:half_open")
                    dispatchBleCommand("action:sit")
                    return true
                }
                // Switch Pro X Button (Half Close Claw / Leap Robot)
                KeyEvent.KEYCODE_BUTTON_X -> {
                    dispatchBleCommand("claw:half_close")
                    dispatchBleCommand("action:leap_forward")
                    return true
                }
                // Switch Pro L Button (Stretch Down)
                KeyEvent.KEYCODE_BUTTON_L1 -> {
                    dispatchBleCommand("claw:open")
                    dispatchBleCommand("action:stretch_down")
                    return true
                }
                // Switch Pro R Button (Stretch Back)
                KeyEvent.KEYCODE_BUTTON_R1 -> {
                    dispatchBleCommand("claw:close")
                    dispatchBleCommand("action:stretch_back")
                    return true
                }
                // Switch Pro ZL Button (Crawl)
                KeyEvent.KEYCODE_BUTTON_L2 -> {
                    dispatchBleCommand("claw:half_open")
                    dispatchBleCommand("action:crawl")
                    return true
                }
                // D-Pad Controls
                KeyEvent.KEYCODE_DPAD_UP -> {
                    dispatchBleCommand("claw:open")
                    dispatchBleCommand("action:forward")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    dispatchBleCommand("claw:close")
                    dispatchBleCommand("action:backward")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    dispatchBleCommand("claw:half_open")
                    dispatchBleCommand("action:left_wave")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    dispatchBleCommand("claw:half_close")
                    dispatchBleCommand("action:right_wave")
                    return true
                }
            }
        }
        return super.onKeyEvent(event)
    }

    private fun dispatchBleCommand(cmd: String) {
        if (RobotBleController.isConnected) {
            RobotBleController.sendBleCommand(cmd)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        isServiceEnabled = false
        super.onDestroy()
    }
}