package com.autonetswitch.app.shizuku;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * 手写的 AIDL 等价物（避免构建期依赖 x86_64 的 aidl 原生工具）。
 * 客户端 Proxy 与服务端 Stub 都是本类的代码，事务号与描述符自洽。
 */
public interface IUserService extends IInterface {

    String DESCRIPTOR = "com.autonetswitch.app.shizuku.IUserService";

    int TRANSACTION_ping = IBinder.FIRST_CALL_TRANSACTION;
    int TRANSACTION_exec = IBinder.FIRST_CALL_TRANSACTION + 1;
    int TRANSACTION_setWifiEnabled = IBinder.FIRST_CALL_TRANSACTION + 2;
    int TRANSACTION_setDataEnabled = IBinder.FIRST_CALL_TRANSACTION + 3;
    int TRANSACTION_setUserDataEnabled = IBinder.FIRST_CALL_TRANSACTION + 4;
    int TRANSACTION_setAirplaneMode = IBinder.FIRST_CALL_TRANSACTION + 5;
    int TRANSACTION_setDefaultDataSubId = IBinder.FIRST_CALL_TRANSACTION + 6;

    int ping() throws RemoteException;

    String exec(String cmd) throws RemoteException;

    String setWifiEnabled(boolean enable) throws RemoteException;

    String setDataEnabled(boolean enable) throws RemoteException;

    String setUserDataEnabled(int subId, boolean enable) throws RemoteException;

    String setAirplaneMode(boolean enable) throws RemoteException;

    String setDefaultDataSubId(int subId) throws RemoteException;

    abstract class Stub extends Binder implements IUserService {

        public Stub() {
            this.attachInterface(this, DESCRIPTOR);
        }

        public static IUserService asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface in = obj.queryLocalInterface(DESCRIPTOR);
            if (in instanceof IUserService) {
                return (IUserService) in;
            }
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case INTERFACE_TRANSACTION: {
                    reply.writeString(DESCRIPTOR);
                    return true;
                }
                case TRANSACTION_ping: {
                    data.enforceInterface(DESCRIPTOR);
                    int result = ping();
                    reply.writeNoException();
                    reply.writeInt(result);
                    return true;
                }
                case TRANSACTION_exec: {
                    data.enforceInterface(DESCRIPTOR);
                    String arg = data.readString();
                    String result = exec(arg);
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                case TRANSACTION_setWifiEnabled: {
                    data.enforceInterface(DESCRIPTOR);
                    boolean arg = data.readInt() != 0;
                    String result = setWifiEnabled(arg);
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                case TRANSACTION_setDataEnabled: {
                    data.enforceInterface(DESCRIPTOR);
                    boolean arg = data.readInt() != 0;
                    String result = setDataEnabled(arg);
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                case TRANSACTION_setUserDataEnabled: {
                    data.enforceInterface(DESCRIPTOR);
                    int subId = data.readInt();
                    boolean arg = data.readInt() != 0;
                    String result = setUserDataEnabled(subId, arg);
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                case TRANSACTION_setAirplaneMode: {
                    data.enforceInterface(DESCRIPTOR);
                    boolean arg = data.readInt() != 0;
                    String result = setAirplaneMode(arg);
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                case TRANSACTION_setDefaultDataSubId: {
                    data.enforceInterface(DESCRIPTOR);
                    int subId = data.readInt();
                    String result = setDefaultDataSubId(subId);
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static class Proxy implements IUserService {

            private final IBinder remote;

            Proxy(IBinder remote) {
                this.remote = remote;
            }

            @Override
            public IBinder asBinder() {
                return remote;
            }

            private Parcel obtain() {
                Parcel p = Parcel.obtain();
                p.writeInterfaceToken(DESCRIPTOR);
                return p;
            }

            private String callString(int code, Parcel data) throws RemoteException {
                Parcel reply = Parcel.obtain();
                try {
                    remote.transact(code, data, reply, 0);
                    reply.readException();
                    return reply.readString();
                } finally {
                    reply.recycle();
                }
            }

            @Override
            public int ping() throws RemoteException {
                Parcel data = obtain();
                Parcel reply = Parcel.obtain();
                try {
                    remote.transact(TRANSACTION_ping, data, reply, 0);
                    reply.readException();
                    return reply.readInt();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public String exec(String cmd) throws RemoteException {
                Parcel data = obtain();
                try {
                    data.writeString(cmd);
                    return callString(TRANSACTION_exec, data);
                } finally {
                    data.recycle();
                }
            }

            @Override
            public String setWifiEnabled(boolean enable) throws RemoteException {
                Parcel data = obtain();
                try {
                    data.writeInt(enable ? 1 : 0);
                    return callString(TRANSACTION_setWifiEnabled, data);
                } finally {
                    data.recycle();
                }
            }

            @Override
            public String setDataEnabled(boolean enable) throws RemoteException {
                Parcel data = obtain();
                try {
                    data.writeInt(enable ? 1 : 0);
                    return callString(TRANSACTION_setDataEnabled, data);
                } finally {
                    data.recycle();
                }
            }

            @Override
            public String setUserDataEnabled(int subId, boolean enable) throws RemoteException {
                Parcel data = obtain();
                try {
                    data.writeInt(subId);
                    data.writeInt(enable ? 1 : 0);
                    return callString(TRANSACTION_setUserDataEnabled, data);
                } finally {
                    data.recycle();
                }
            }

            @Override
            public String setAirplaneMode(boolean enable) throws RemoteException {
                Parcel data = obtain();
                try {
                    data.writeInt(enable ? 1 : 0);
                    return callString(TRANSACTION_setAirplaneMode, data);
                } finally {
                    data.recycle();
                }
            }

            @Override
            public String setDefaultDataSubId(int subId) throws RemoteException {
                Parcel data = obtain();
                try {
                    data.writeInt(subId);
                    return callString(TRANSACTION_setDefaultDataSubId, data);
                } finally {
                    data.recycle();
                }
            }
        }
    }
}
