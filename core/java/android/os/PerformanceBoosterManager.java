package android.os;

import android.annotation.SystemService;
import android.content.Context;

/** @hide */
@SystemService(Context.PERFORMANCE_BOOSTER_SERVICE)
public class PerformanceBoosterManager {
    private static final String TAG = "PerformanceBoosterManager";
    
    private final Context mContext;
    private final IPerformanceBoosterService mService;
    
    public PerformanceBoosterManager(Context context, IPerformanceBoosterService service) {
        mContext = context;
        mService = service;
    }
    
    public void forceCleanupRam() {
        try {
            mService.forceCleanupRam();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
    
    public void applyPerformanceProfile() {
        try {
            mService.applyPerformanceProfile();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
    
    public void applyBatteryProfile() {
        try {
            mService.applyBatteryProfile();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
