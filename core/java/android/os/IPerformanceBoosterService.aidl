package android.os;

/** {@hide} */
interface IPerformanceBoosterService {
    void forceCleanupRam();
    void applyPerformanceProfile();
    void applyBatteryProfile();
}
