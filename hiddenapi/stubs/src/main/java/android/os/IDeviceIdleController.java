package android.os;

public interface IDeviceIdleController extends IInterface {
    void addPowerSaveTempWhitelistApp(String packageName, long duration, int userId, String reason) throws RemoteException;

    void addPowerSaveTempWhitelistApp(String packageName, long duration, int userId, int reasonCode, String reason) throws RemoteException;

    abstract class Stub extends Binder implements IDeviceIdleController {

        public static IDeviceIdleController asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }
    }
}
