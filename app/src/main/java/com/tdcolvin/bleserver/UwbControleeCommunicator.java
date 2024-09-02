package com.tdcolvin.bleserver;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.uwb.RangingParameters;
import androidx.core.uwb.RangingResult;
import androidx.core.uwb.UwbAddress;
import androidx.core.uwb.UwbClientSessionScope;
import androidx.core.uwb.UwbComplexChannel;
import androidx.core.uwb.UwbControleeSessionScope;
import androidx.core.uwb.UwbDevice;
import androidx.core.uwb.UwbManager;
import androidx.core.uwb.rxjava3.UwbClientSessionScopeRx;
import androidx.core.uwb.rxjava3.UwbManagerRx;

import com.google.firebase.crashlytics.buildtools.reloc.com.google.common.primitives.Shorts;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.rxjava3.disposables.Disposable;

public class UwbControleeCommunicator {

    // init variables
    private final Context context;
    private final UwbManager uwbManager;
    private final AtomicReference<Disposable> rangingResultObservable = new AtomicReference<>(null);
    private final AtomicReference<UwbClientSessionScope> currentUwbSessionScope = new AtomicReference<>(null);

    public UwbControleeCommunicator(Context context) {
        // init in Class
        this.context = context;
        this.uwbManager = UwbManager.createInstance(context);

        if (rangingResultObservable.get() != null) {
            rangingResultObservable.get().dispose();
            rangingResultObservable.set(null);
        }

        new Thread(() -> currentUwbSessionScope.set(UwbManagerRx.controleeSessionScopeSingle(uwbManager).blockingGet())).start();
    }

    public String getUwbAddress() {
        UwbControleeSessionScope controleeSessionScope = (UwbControleeSessionScope) currentUwbSessionScope.get();
        if (controleeSessionScope != null) {
            UwbAddress localAddress = controleeSessionScope.getLocalAddress();
            return Shorts.fromByteArray(localAddress.getAddress()) + "";
        } else {
            return "UWB session not initialized";
        }
    }

    public void startCommunication(String controllerAddress, String controllerChannel) {
        try {
            int otherSideLocalAddress = Integer.parseInt(controllerAddress);
            UwbAddress partnerAddress = new UwbAddress(Shorts.toByteArray((short) otherSideLocalAddress));

            int channelPreamble = Integer.parseInt(controllerChannel);
            UwbComplexChannel uwbComplexChannel = new UwbComplexChannel(9, channelPreamble);

            RangingParameters partnerParameters = new RangingParameters(
                    RangingParameters.CONFIG_MULTICAST_DS_TWR,
                    12345,
                    0,
                    new byte[]{0, 0, 0, 0, 0, 0, 0, 0},
                    null,
                    uwbComplexChannel,
                    Collections.singletonList(new UwbDevice(partnerAddress)),
                    RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC
            );

            rangingResultObservable.set(UwbClientSessionScopeRx.rangingResultsObservable(currentUwbSessionScope.get(), partnerParameters).subscribe(
                    rangingResult -> {
                        if (rangingResult instanceof RangingResult.RangingResultPosition) {
                            RangingResult.RangingResultPosition rangingResultPosition = (RangingResult.RangingResultPosition) rangingResult;
                            if (rangingResultPosition.getPosition().getDistance() != null) {
                                String distance = "Distance: " + rangingResultPosition.getPosition().getDistance().getValue();
                                Log.d("Distance", distance);
                            } else {
                                Log.e("UWB", "Distance is null");
                            }
                        } else {
                            Log.e("UWB", "Unexpected result type: " + rangingResult.getClass().getName());
                            Log.e("UWB", "CONNECTION LOST");
                        }

                    },
                    throwable -> Log.e("UWB", "Error during ranging", throwable),
                    () -> Log.d("UWB", "Completed the observing of RangingResults")
            ));

        } catch (NumberFormatException e) {
            System.out.println("Caught Exception: " + e);
        }
    }
}