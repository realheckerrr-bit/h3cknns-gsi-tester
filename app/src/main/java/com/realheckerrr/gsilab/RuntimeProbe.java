package com.realheckerrr.gsilab;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/** Reports host capabilities without claiming that an ordinary APK can create a VM. */
public final class RuntimeProbe {
    public final boolean arm64;
    public final boolean virtualizationFeature;
    public final boolean manageVmPermission;

    private RuntimeProbe(boolean arm64, boolean virtualizationFeature, boolean manageVmPermission) {
        this.arm64 = arm64;
        this.virtualizationFeature = virtualizationFeature;
        this.manageVmPermission = manageVmPermission;
    }

    public static RuntimeProbe inspect(Context context) {
        boolean arm64 = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            for (String abi : Build.SUPPORTED_64_BIT_ABIS) {
                if ("arm64-v8a".equals(abi)) {
                    arm64 = true;
                    break;
                }
            }
        }
        PackageManager pm = context.getPackageManager();
        boolean avf = pm.hasSystemFeature("android.software.virtualization_framework");
        boolean permission = context.checkSelfPermission("android.permission.MANAGE_VIRTUAL_MACHINE")
                == PackageManager.PERMISSION_GRANTED;
        return new RuntimeProbe(arm64, avf, permission);
    }

    public boolean canUsePrivilegedAvf() {
        return arm64 && virtualizationFeature && manageVmPermission;
    }

    public String render() {
        StringBuilder out = new StringBuilder();
        out.append("HOST RUNTIME\n");
        out.append("  ARM64 host: ").append(arm64 ? "yes" : "no").append('\n');
        out.append("  AVF feature advertised: ").append(virtualizationFeature ? "yes" : "no").append('\n');
        out.append("  MANAGE_VIRTUAL_MACHINE: ").append(manageVmPermission ? "granted" : "not granted").append('\n');
        out.append("  direct AVF app backend: privileged\n");
        out.append("  bundled QEMU backend: checked by VM backend\n");
        if (!arm64) {
            out.append("  result: this build targets ARM64 guests, but the host is not ARM64.\n");
        } else if (!virtualizationFeature) {
            out.append("  result: this Android build does not advertise AVF.\n");
        } else if (!manageVmPermission) {
            out.append("  result: AVF is privileged on this device; a normal sideloaded APK cannot use it.\n");
        } else {
            out.append("  result: AVF is present, but this APK still needs a privileged backend and guest boot bundle.\n");
        }
        return out.toString();
    }
}
