/*
 * Copyright (C) 2025 the RisingOS Revived Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.android;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Slog;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import android.os.IPerformanceBoosterService;

public class PerformanceBoosterService extends IPerformanceBoosterService.Stub {
    private static final String TAG = "PerformanceBooster";
    
    // CPU related paths
    private static final String CPU_GOVERNORS_PATH = "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_governor";
    private static final String CPU_MIN_FREQ_PATH = "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_min_freq";
    private static final String CPU_MAX_FREQ_PATH = "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_max_freq";
    private static final String CPU_CURRENT_FREQ_PATH = "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_cur_freq";
    
    // Thermal paths
    private static final String THERMAL_MODE_PATH = "/sys/class/thermal/thermal_zone0/mode";
    private static final String THERMAL_POLICY_PATH = "/sys/class/thermal/thermal_policy";
    
    private static final int RAM_CLEANUP_INTERVAL = 30; // seconds
    private static final int THERMAL_CHECK_INTERVAL = 15; // seconds
    private static final int DEEP_SLEEP_CHECK_INTERVAL = 60; // seconds
    private static final int CORE_COUNT = Runtime.getRuntime().availableProcessors();
    
    private PowerManager.WakeLock wakeLock;
    private PowerManager powerManager;
    private ScheduledExecutorService scheduler;
    private Context mContext;
    private long lastUserActivityTime;
    private static final long INACTIVITY_THRESHOLD = 2 * 60 * 1000; // 2 minutes in milliseconds
    
    public PerformanceBoosterService(Context context) {
        mContext = context;
        lastUserActivityTime = SystemClock.uptimeMillis();
        init();
    }
    
    private void init() {
        Slog.i(TAG, "Performance Booster Service starting");
        
        powerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PerformanceBooster:WakeLock");
        scheduler = Executors.newScheduledThreadPool(3);
        
        // Start the optimization tasks automatically
        startOptimizations();
    }
    
    private void startOptimizations() {
        // Apply initial configurations
        applyPerformanceProfile();
        configureGovernors();
        
        // Schedule periodic RAM cleanup
        scheduler.scheduleAtFixedRate(
                this::cleanupRam,
                RAM_CLEANUP_INTERVAL,
                RAM_CLEANUP_INTERVAL,
                TimeUnit.SECONDS
        );
        
        // Schedule thermal management
        scheduler.scheduleAtFixedRate(
                this::manageThermals,
                THERMAL_CHECK_INTERVAL,
                THERMAL_CHECK_INTERVAL,
                TimeUnit.SECONDS
        );
        
        // Add automatic CPU frequency management
        scheduler.scheduleAtFixedRate(
                this::automaticCpuManagement,
                60,
                60,
                TimeUnit.SECONDS
        );
        
        // Add automatic deep sleep check
        scheduler.scheduleAtFixedRate(
                this::checkForDeepSleep,
                DEEP_SLEEP_CHECK_INTERVAL,
                DEEP_SLEEP_CHECK_INTERVAL,
                TimeUnit.SECONDS
        );
    }
    
    /**
     * Checks if the device has been inactive and triggers deep sleep if needed
     */
    private void checkForDeepSleep() {
        long currentTime = SystemClock.uptimeMillis();
        boolean isScreenOn = powerManager.isInteractive();
        long inactiveTime = currentTime - lastUserActivityTime;
        
        Slog.d(TAG, "Checking for deep sleep, inactive time: " + (inactiveTime / 1000) + "s, screen on: " + isScreenOn);
        
        // Update last activity time if screen is on (assuming there's user activity)
        if (isScreenOn) {
            lastUserActivityTime = currentTime;
            // If screen is on but we're in a low activity state, apply moderate power saving
            if (getSystemLoad() < 0.2) {
                Slog.d(TAG, "Screen on but low activity, applying moderate power saving");
                applyConservativeCpuSettings();
            }
            return;
        }
        
        // If screen is off and inactive for threshold time, enter deep sleep
        if (!isScreenOn && inactiveTime > INACTIVITY_THRESHOLD) {
            Slog.i(TAG, "Device inactive for " + (inactiveTime / 1000) + " seconds, entering deep sleep");
            enterDeepSleep();
        }
    }
    
    /**
     * Put device into deep sleep state
     */
    private void enterDeepSleep() {
        Slog.i(TAG, "Entering deep sleep mode");
        
        // Force all CPUs to minimum frequency
        for (int i = 0; i < CORE_COUNT; i++) {
            List<String> freqList = getAvailableFrequencies(i);
            if (!freqList.isEmpty()) {
                String minFreq = freqList.get(0);
                writeToFile(String.format(CPU_MAX_FREQ_PATH, i), minFreq);
                writeToFile(String.format(CPU_GOVERNORS_PATH, i), "powersave");
            }
        }
        
        // Kill background processes
        cleanupRam();
        
        // Set very aggressive VM parameters to minimize background activity
        writeToFile("/proc/sys/vm/swappiness", "1");
        writeToFile("/proc/sys/vm/dirty_ratio", "90");
        writeToFile("/proc/sys/vm/dirty_background_ratio", "70");
        writeToFile("/proc/sys/vm/dirty_expire_centisecs", "3000");
        
        // Sync and flush data
        try {
            Runtime.getRuntime().exec("sync");
        } catch (IOException e) {
            Slog.e(TAG, "Failed to sync data before deep sleep", e);
        }
        
        // Apply aggressive thermal policy
        writeToFile(THERMAL_MODE_PATH, "disabled");
        
        // Lower GPU frequency if possible
        writeToFile("/sys/class/kgsl/kgsl-3d0/devfreq/max_freq", getLowGpuFreq());
        
        Slog.i(TAG, "Device entered deep sleep state");
    }
    
    /**
     * Automatically manages CPU frequencies based on system load
     */
    private void automaticCpuManagement() {
        try {
            // Get current CPU load
            double load = getSystemLoad();
            Slog.d(TAG, "Current system load: " + load);
            
            if (load > 0.7) {
                // High load - maximize performance
                Slog.d(TAG, "High system load detected, maximizing performance");
                for (int i = 0; i < CORE_COUNT; i++) {
                    List<String> freqList = getAvailableFrequencies(i);
                    if (!freqList.isEmpty()) {
                        String maxFreq = freqList.get(freqList.size() - 1);
                        writeToFile(String.format(CPU_MAX_FREQ_PATH, i), maxFreq);
                        writeToFile(String.format(CPU_GOVERNORS_PATH, i), "performance");
                    }
                }
                
                // Update activity time since there's high system usage
                lastUserActivityTime = SystemClock.uptimeMillis();
                
            } else if (load < 0.3) {
                // Low load - conserve power
                Slog.d(TAG, "Low system load detected, conserving power");
                applyConservativeCpuSettings();
            } else {
                // Medium load - balanced profile
                Slog.d(TAG, "Normal system load, applying balanced profile");
                configureGovernors();
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error in automatic CPU management", e);
        }
    }
    
    /**
     * Applies conservative CPU frequencies
     */
    private void applyConservativeCpuSettings() {
        for (int i = 0; i < CORE_COUNT; i++) {
            // Get available frequencies for this CPU
            List<String> freqList = getAvailableFrequencies(i);
            if (freqList.isEmpty()) continue;
            
            // Get frequencies at 60% of max (for underclock)
            int maxIndex = freqList.size() - 1;
            int conservativeIndex = Math.max(0, (int)(maxIndex * 0.6));
            String conservativeFreq = freqList.get(conservativeIndex);
            
            // Apply the underclock
            writeToFile(String.format(CPU_MAX_FREQ_PATH, i), conservativeFreq);
            Slog.d(TAG, "CPU" + i + " underclocked to " + conservativeFreq);
        }
    }
    
    /**
     * Clean RAM periodically
     */
    private void cleanupRam() {
        Slog.d(TAG, "Cleaning up RAM");
        
        try {
            // Get list of background processes
            List<String> backgroundProcesses = getBackgroundProcesses();
            
            // Kill non-essential background processes
            for (String process : backgroundProcesses) {
                if (!isSystemCritical(process)) {
                    Runtime.getRuntime().exec("am kill " + process);
                }
            }
            
            // Force garbage collection
            System.gc();
            
            // Drop caches
            writeToFile("/proc/sys/vm/drop_caches", "3");
            Slog.d(TAG, "RAM cleanup completed");
        } catch (Exception e) {
            Slog.e(TAG, "Error during RAM cleanup", e);
        }
    }
    
    /**
     * Apply performance profile for smooth operation
     */
    public void applyPerformanceProfile() {
        // Set kernel parameters for better responsiveness
        writeToFile("/proc/sys/vm/swappiness", "10");
        writeToFile("/proc/sys/vm/dirty_ratio", "20");
        writeToFile("/proc/sys/vm/dirty_background_ratio", "5");
        writeToFile("/proc/sys/kernel/sched_latency_ns", "10000000");
        writeToFile("/proc/sys/kernel/sched_min_granularity_ns", "1250000");
        
        // Configure I/O Scheduler
        for (String device : getBlockDevices()) {
            writeToFile("/sys/block/" + device + "/queue/scheduler", "cfq");
            writeToFile("/sys/block/" + device + "/queue/read_ahead_kb", "1024");
        }
        
        // Apply balanced CPU settings
        configureGovernors();
        
        // Update activity time
        lastUserActivityTime = SystemClock.uptimeMillis();
    }
    
    /**
     * Manage thermals to prevent overheating
     */
    private void manageThermals() {
        // Read current temperature
        int currentTemp = readCurrentTemperature();
        Slog.d(TAG, "Current temperature: " + currentTemp);
        
        if (currentTemp > 45) {
            // Critical temperature: aggressive throttling
            Slog.w(TAG, "Critical temperature detected! Applying aggressive throttling");
            for (int i = 0; i < CORE_COUNT; i++) {
                List<String> freqList = getAvailableFrequencies(i);
                if (!freqList.isEmpty()) {
                    String minFreq = freqList.get(0);
                    String lowFreq = freqList.get(Math.min(2, freqList.size() - 1));
                    writeToFile(String.format(CPU_MAX_FREQ_PATH, i), lowFreq);
                    writeToFile(String.format(CPU_MIN_FREQ_PATH, i), minFreq);
                    writeToFile(String.format(CPU_GOVERNORS_PATH, i), "powersave");
                }
            }
            
            // Clean RAM to reduce heat-generating processes
            cleanupRam();
            
            // Lower GPU frequency if possible
            writeToFile("/sys/class/kgsl/kgsl-3d0/devfreq/max_freq", getLowGpuFreq());
            
        } else if (currentTemp > 40) {
            // High temperature: moderate throttling
            Slog.d(TAG, "High temperature: applying thermal management");
            for (int i = 0; i < CORE_COUNT; i++) {
                List<String> freqList = getAvailableFrequencies(i);
                if (!freqList.isEmpty()) {
                    int midIndex = freqList.size() / 2;
                    String midFreq = freqList.get(midIndex);
                    writeToFile(String.format(CPU_MAX_FREQ_PATH, i), midFreq);
                    writeToFile(String.format(CPU_GOVERNORS_PATH, i), "conservative");
                }
            }
            
        } else {
            // Normal temperature: balanced operation
            Slog.d(TAG, "Temperature normal, maintaining balanced profile");
            configureGovernors();
        }
    }
    
    /**
     * Helper method to configure CPU governors for optimal performance
     */
    private void configureGovernors() {
        for (int i = 0; i < CORE_COUNT; i++) {
            if (i < 4) {
                // For efficiency cores
                writeToFile(String.format(CPU_GOVERNORS_PATH, i), "interactive");
            } else {
                // For performance cores
                writeToFile(String.format(CPU_GOVERNORS_PATH, i), "schedutil");
            }
        }
    }
    
    // Helper methods
    
    private double getSystemLoad() {
        try {
            String loadAvg = readFromFile("/proc/loadavg");
            if (loadAvg != null && !loadAvg.isEmpty()) {
                String[] parts = loadAvg.split(" ");
                if (parts.length > 0) {
                    return Double.parseDouble(parts[0]) / CORE_COUNT;
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error reading system load", e);
        }
        return 0.5; // Default medium load
    }
    
    private int readCurrentTemperature() {
        try {
            // Try common thermal zone paths
            for (int i = 0; i < 10; i++) {
                String path = "/sys/class/thermal/thermal_zone" + i + "/temp";
                String temp = readFromFile(path);
                if (temp != null && !temp.isEmpty()) {
                    int tempValue = Integer.parseInt(temp.trim());
                    // Convert to Celsius if necessary (some devices report in milliCelsius)
                    return tempValue > 1000 ? tempValue / 1000 : tempValue;
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error reading temperature", e);
        }
        return 30; // Default fallback temperature
    }
    
    private List<String> getAvailableFrequencies(int cpuNum) {
        List<String> freqs = new ArrayList<>();
        String freqList = readFromFile("/sys/devices/system/cpu/cpu" + cpuNum + "/cpufreq/scaling_available_frequencies");
        
        if (freqList != null && !freqList.isEmpty()) {
            String[] frequencies = freqList.trim().split("\\s+");
            for (String freq : frequencies) {
                freqs.add(freq);
            }
        }
        
        return freqs;
    }
    
    private List<String> getBlockDevices() {
        List<String> devices = new ArrayList<>();
        File blockDir = new File("/sys/block/");
        
        if (blockDir.exists() && blockDir.isDirectory()) {
            File[] files = blockDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory() && (file.getName().startsWith("sd") || 
                            file.getName().startsWith("mmcblk") || 
                            file.getName().startsWith("dm"))) {
                        devices.add(file.getName());
                    }
                }
            }
        }
        
        return devices;
    }
    
    private List<String> getBackgroundProcesses() {
        List<String> processes = new ArrayList<>();
        try {
            Process process = Runtime.getRuntime().exec("ps -A");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            
            while ((line = reader.readLine()) != null) {
                if (line.contains("app_") || line.contains("com.")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 9) {
                        processes.add(parts[8]); // Package name is typically in position 8
                    }
                }
            }
            reader.close();
        } catch (IOException e) {
            Slog.e(TAG, "Failed to get background processes", e);
        }
        
        return processes;
    }
    
    private boolean isSystemCritical(String processName) {
        // List of critical system processes that should not be killed
        String[] criticalProcesses = {
            "system", "systemui", "phone", "bluetooth", "media", "nfc", 
            "com.android.systemui", "com.android.phone", "com.android.bluetooth",
            "android.process.acore", "android.process.media"
        };
        
        for (String critical : criticalProcesses) {
            if (processName.toLowerCase().contains(critical)) {
                return true;
            }
        }
        
        return false;
    }
    
    private String getLowGpuFreq() {
        String freqList = readFromFile("/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies");
        if (freqList != null && !freqList.isEmpty()) {
            String[] frequencies = freqList.trim().split("\\s+");
            // Return the lowest third of available frequencies
            int index = Math.max(0, frequencies.length / 3);
            return frequencies[index];
        }
        return ""; // Default empty if unable to determine
    }
    
    private String readFromFile(String path) {
        try {
            BufferedReader reader = new BufferedReader(new FileReader(new File(path)));
            String line = reader.readLine();
            reader.close();
            return line;
        } catch (IOException e) {
            return null;
        }
    }
    
    private void writeToFile(String path, String value) {
        try {
            File file = new File(path);
            if (file.exists() && file.canWrite()) {
                BufferedWriter writer = new BufferedWriter(new FileWriter(file));
                writer.write(value);
                writer.close();
                Slog.d(TAG, "Successfully wrote " + value + " to " + path);
            } else {
                Slog.w(TAG, "Cannot write to " + path + ": file doesn't exist or isn't writable");
            }
        } catch (IOException e) {
            Slog.e(TAG, "Error writing to " + path, e);
        }
    }
    
    public void cleanup() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        
        // Restore default settings
        restoreDefaultSettings();
        
        Slog.i(TAG, "Performance Booster Service stopped");
    }
    
    private void restoreDefaultSettings() {
        // Restore CPU governors to default
        for (int i = 0; i < CORE_COUNT; i++) {
            writeToFile(String.format(CPU_GOVERNORS_PATH, i), "schedutil");
        }
        
        // Restore thermal mode
        writeToFile(THERMAL_MODE_PATH, "enabled");
        
        // Restore VM settings
        writeToFile("/proc/sys/vm/swappiness", "60");
        writeToFile("/proc/sys/vm/dirty_ratio", "30");
        writeToFile("/proc/sys/vm/dirty_background_ratio", "10");
    }

    @Override
    public void forceCleanupRam() {
        cleanupRam();
    }
    
    @Override
    public void applyBatteryProfile() {
        // Apply conservative CPU settings and trigger deep sleep
        applyConservativeCpuSettings();
        enterDeepSleep();
    }
}
