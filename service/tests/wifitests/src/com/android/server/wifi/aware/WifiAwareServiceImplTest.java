/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi.aware;

import static com.android.server.wifi.WifiSettingsConfigStore.WIFI_VERBOSE_LOGGING_ENABLED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.wifi.WifiScanner;
import android.net.wifi.aware.Characteristics;
import android.net.wifi.aware.ConfigRequest;
import android.net.wifi.aware.IWifiAwareDiscoverySessionCallback;
import android.net.wifi.aware.IWifiAwareEventCallback;
import android.net.wifi.aware.IWifiAwareMacAddressProvider;
import android.net.wifi.aware.MacAddrMapping;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.WifiAwareDataPathSecurityConfig;
import android.os.Build;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.util.SparseArray;
import android.util.SparseIntArray;

import androidx.test.filters.SmallTest;

import com.android.modules.utils.build.SdkLevel;
import com.android.server.wifi.HalDeviceManager;
import com.android.server.wifi.InterfaceConflictManager;
import com.android.server.wifi.WifiBaseTest;
import com.android.server.wifi.WifiSettingsConfigStore;
import com.android.server.wifi.util.NetdWrapper;
import com.android.server.wifi.util.WifiPermissionsUtil;
import com.android.server.wifi.util.WifiPermissionsWrapper;
import com.android.wifi.resources.R;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;


/**
 * Unit test harness for WifiAwareStateManager.
 */
@SmallTest
public class WifiAwareServiceImplTest extends WifiBaseTest {
    private static final int MAX_LENGTH = 255;

    private WifiAwareServiceImplSpy mDut;
    private int mDefaultUid = 1500;
    private String mPackageName = "some.package";
    private String mFeatureId = "some.feature";
    private TestLooper mMockLooper;
    private Bundle mExtras = new Bundle();

    @Mock
    private Context mContextMock;
    @Mock
    private HandlerThread mHandlerThreadMock;
    @Mock
    private PackageManager mPackageManagerMock;
    @Mock
    private WifiAwareStateManager mAwareStateManagerMock;
    @Mock
    private WifiAwareShellCommand mWifiAwareShellCommandMock;
    @Mock
    private IBinder mBinderMock;
    @Mock
    private IWifiAwareEventCallback mCallbackMock;
    @Mock
    private IWifiAwareDiscoverySessionCallback mSessionCallbackMock;
    @Mock private WifiAwareMetrics mAwareMetricsMock;
    @Mock private WifiPermissionsUtil mWifiPermissionsUtil;
    @Mock private WifiPermissionsWrapper mPermissionsWrapperMock;
    @Mock private WifiSettingsConfigStore mWifiSettingsConfigStore;
    @Mock private InterfaceConflictManager mInterfaceConflictManager;

    /**
     * Using instead of spy to avoid native crash failures - possibly due to
     * spy's copying of state.
     */
    private class WifiAwareServiceImplSpy extends WifiAwareServiceImpl {
        public int fakeUid;

        WifiAwareServiceImplSpy(Context context) {
            super(context);
        }

        /**
         * Return the fake UID instead of the real one: pseudo-spy
         * implementation.
         */
        @Override
        public int getMockableCallingUid() {
            return fakeUid;
        }
    }

    /**
     * Initializes mocks.
     */
    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        mMockLooper = new TestLooper();

        when(mHandlerThreadMock.getLooper()).thenReturn(mMockLooper.getLooper());

        AppOpsManager appOpsMock = mock(AppOpsManager.class);
        when(mContextMock.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(appOpsMock);

        when(mContextMock.getApplicationContext()).thenReturn(mContextMock);
        when(mContextMock.getPackageManager()).thenReturn(mPackageManagerMock);
        when(mPackageManagerMock.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE))
                .thenReturn(true);
        when(mPackageManagerMock.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT))
                .thenReturn(true);
        when(mAwareStateManagerMock.getCharacteristics()).thenReturn(getCharacteristics());
        when(mWifiSettingsConfigStore.get(eq(WIFI_VERBOSE_LOGGING_ENABLED))).thenReturn(true);
        // mock target SDK version to be pre-T by default to keep existing tests working.
        when(mWifiPermissionsUtil.isTargetSdkLessThan(any(), eq(Build.VERSION_CODES.TIRAMISU),
                anyInt())).thenReturn(true);

        when(mInterfaceConflictManager.manageInterfaceConflictForStateMachine(any(), any(), any(),
                any(), any(), eq(HalDeviceManager.HDM_CREATE_IFACE_NAN), any(), anyBoolean()))
                .thenReturn(InterfaceConflictManager.ICM_EXECUTE_COMMAND);

        mDut = new WifiAwareServiceImplSpy(mContextMock);
        Resources resources = mock(Resources.class);
        when(mContextMock.getResources()).thenReturn(resources);
        when(resources.getInteger(
                        R.integer.config_wifiVerboseLoggingAlwaysOnLevel)).thenReturn(0);
        mDut.fakeUid = mDefaultUid;
        mDut.start(mHandlerThreadMock, mAwareStateManagerMock, mWifiAwareShellCommandMock,
                mAwareMetricsMock, mWifiPermissionsUtil, mPermissionsWrapperMock,
                mWifiSettingsConfigStore,
                mock(WifiAwareNativeManager.class), mock(WifiAwareNativeApi.class),
                mock(WifiAwareNativeCallback.class), mock(NetdWrapper.class),
                mInterfaceConflictManager);
        mMockLooper.dispatchAll();
        verify(mAwareStateManagerMock).start(eq(mContextMock), any(), eq(mAwareMetricsMock),
                eq(mWifiPermissionsUtil), eq(mPermissionsWrapperMock), any(), any(),
                eq(mInterfaceConflictManager));
    }

    /**
     * Validate isUsageEnabled() function
     */
    @Test
    public void testIsUsageEnabled() {
        mDut.isUsageEnabled();

        verify(mAwareStateManagerMock).isUsageEnabled();
    }

    @Test
    public void testGetAwareResources() {
        mDut.getAvailableAwareResources();
        verify(mAwareStateManagerMock).getAvailableAwareResources();
    }

    /**
     * Validate enableInstantCommunicationMode() and isInstantCommunicationModeEnabled() function
     */
    @Test
    public void testInstantCommunicationMode() {
        mDut.isInstantCommunicationModeEnabled();
        verify(mAwareStateManagerMock).isInstantCommModeGlobalEnable();

        // Non-system package could not enable this mode.
        when(mWifiPermissionsUtil.isSystem(anyString(), anyInt())).thenReturn(false);
        mDut.enableInstantCommunicationMode(mPackageName, true);
        verify(mAwareStateManagerMock, never()).enableInstantCommunicationMode(eq(true));

        when(mWifiPermissionsUtil.isSystem(anyString(), anyInt())).thenReturn(true);
        mDut.enableInstantCommunicationMode(mPackageName, true);
        verify(mAwareStateManagerMock).enableInstantCommunicationMode(eq(true));

    }


    /**
     * Validate connect() - returns and uses a client ID.
     */
    @Test
    public void testConnect() {
        doConnect();
    }

    /**
     * Validate connect() when a non-null config is passed.
     */
    @Test
    public void testConnectWithConfig() {
        ConfigRequest configRequest = new ConfigRequest.Builder().setMasterPreference(55).build();
        String callingPackage = "com.google.somePackage";
        String callingFeatureId = "com.google.someFeature";
        mDut.connect(mBinderMock, callingPackage, callingFeatureId, mCallbackMock,
                configRequest, false, mExtras);

        verify(mAwareStateManagerMock).connect(anyInt(), anyInt(), anyInt(), eq(callingPackage),
                eq(callingFeatureId), eq(mCallbackMock), eq(configRequest), eq(false), any());
    }

    /**
     * Validate disconnect() - correct pass-through args.
     *
     * @throws Exception
     */
    @Test
    public void testDisconnect() throws Exception {
        int clientId = doConnect();
        mDut.disconnect(clientId, mBinderMock);

        verify(mAwareStateManagerMock).disconnect(clientId);
        validateInternalStateCleanedUp(clientId);
    }

    /**
     * Validate that security exception thrown when attempting operation using
     * an invalid client ID.
     */
    @Test(expected = SecurityException.class)
    public void testFailOnInvalidClientId() {
        mDut.disconnect(-1, mBinderMock);
    }

    /**
     * Validate that security exception thrown when attempting operation using a
     * client ID which was already cleared-up.
     */
    @Test(expected = SecurityException.class)
    public void testFailOnClearedUpClientId() throws Exception {
        int clientId = doConnect();

        mDut.disconnect(clientId, mBinderMock);

        verify(mAwareStateManagerMock).disconnect(clientId);
        validateInternalStateCleanedUp(clientId);

        mDut.disconnect(clientId, mBinderMock);
    }

    /**
     * Validate that trying to use a client ID from a UID which is different
     * from the one that created it fails - and that the internal state is not
     * modified so that a valid call (from the correct UID) will subsequently
     * succeed.
     */
    @Test
    public void testFailOnAccessClientIdFromWrongUid() throws Exception {
        int clientId = doConnect();

        mDut.fakeUid = mDefaultUid + 1;

        /*
         * Not using thrown.expect(...) since want to test that subsequent
         * access works.
         */
        boolean failsAsExpected = false;
        try {
            mDut.disconnect(clientId, mBinderMock);
        } catch (SecurityException e) {
            failsAsExpected = true;
        }

        mDut.fakeUid = mDefaultUid;

        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName("valid.value")
                .build();
        mDut.publish(mPackageName, mFeatureId, clientId, publishConfig, mSessionCallbackMock,
                mExtras);

        verify(mAwareStateManagerMock).publish(clientId, publishConfig, mSessionCallbackMock);
        assertTrue("SecurityException for invalid access from wrong UID thrown", failsAsExpected);
    }

    /**
     * Validate that the RTT feature support is checked when attempting a Publish with ranging.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testFailOnPublishRangingWithoutRttFeature() throws Exception {
        when(mPackageManagerMock.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)).thenReturn(
                false);

        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName("something.valid")
                .setRangingEnabled(true).build();
        int clientId = doConnect();
        IWifiAwareDiscoverySessionCallback mockCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);

        mDut.publish(mPackageName, mFeatureId, clientId, publishConfig, mockCallback, mExtras);
    }

    /**
     * Validate that the RTT feature support is checked when attempting a Subscribe with ranging.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testFailOnSubscribeRangingWithoutRttFeature() throws Exception {
        when(mPackageManagerMock.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)).thenReturn(
                false);

        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().setServiceName(
                "something.valid").setMaxDistanceMm(100).build();
        int clientId = doConnect();
        IWifiAwareDiscoverySessionCallback mockCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);

        mDut.subscribe(mPackageName, mFeatureId, clientId, subscribeConfig, mockCallback, mExtras);
    }


    /**
     * Validates that on binder death we get a disconnect().
     */
    @Test
    public void testBinderDeath() throws Exception {
        ArgumentCaptor<IBinder.DeathRecipient> deathRecipient = ArgumentCaptor
                .forClass(IBinder.DeathRecipient.class);

        int clientId = doConnect();

        verify(mBinderMock).linkToDeath(deathRecipient.capture(), eq(0));
        deathRecipient.getValue().binderDied();
        verify(mAwareStateManagerMock).disconnect(clientId);
        validateInternalStateCleanedUp(clientId);
    }

    /**
     * Validates that sequential connect() calls return increasing client IDs.
     */
    @Test
    public void testClientIdIncrementing() {
        int loopCount = 100;

        InOrder inOrder = inOrder(mAwareStateManagerMock);
        ArgumentCaptor<Integer> clientIdCaptor = ArgumentCaptor.forClass(Integer.class);

        int prevId = 0;
        for (int i = 0; i < loopCount; ++i) {
            mDut.connect(mBinderMock, "", "", mCallbackMock, null, false, mExtras);
            inOrder.verify(mAwareStateManagerMock).connect(clientIdCaptor.capture(), anyInt(),
                    anyInt(), any(), any(), eq(mCallbackMock), any(), eq(false), any());
            int id = clientIdCaptor.getValue();
            if (i != 0) {
                assertTrue("Client ID incrementing", id > prevId);
            }
            prevId = id;
        }
    }

    /**
     * Validate terminateSession() - correct pass-through args.
     */
    @Test
    public void testTerminateSession() {
        int sessionId = 1024;
        int clientId = doConnect();

        mDut.terminateSession(clientId, sessionId);

        verify(mAwareStateManagerMock).terminateSession(clientId, sessionId);
    }

    /**
     * Validate publish() - correct pass-through args.
     */
    @Test
    public void testPublish() {
        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName("something.valid")
                .setRangingEnabled(true).build();
        int clientId = doConnect();
        IWifiAwareDiscoverySessionCallback mockCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);

        mDut.publish(mPackageName, mFeatureId, clientId, publishConfig, mockCallback, mExtras);

        verify(mAwareStateManagerMock).publish(clientId, publishConfig, mockCallback);
    }

    @Test
    public void testPublishPostT() {
        assumeTrue(SdkLevel.isAtLeastT());
        setTargetSdkToT();

        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName("something.valid")
                .setRangingEnabled(true).build();
        int clientId = doConnect();
        IWifiAwareDiscoverySessionCallback mockCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);

        mDut.publish(mPackageName, mFeatureId, clientId, publishConfig, mockCallback, mExtras);

        verify(mAwareStateManagerMock).publish(clientId, publishConfig, mockCallback);
        verify(mWifiPermissionsUtil).enforceNearbyDevicesPermission(any(), eq(true), any());
    }

    @Test(expected = SecurityException.class)
    public void testPublishExceptionPostT() {
        assumeTrue(SdkLevel.isAtLeastT());
        setTargetSdkToT();
        // app has no nearby permission
        doThrow(new SecurityException()).when(mWifiPermissionsUtil)
                .enforceNearbyDevicesPermission(any(), anyBoolean(), any());
        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName("something.valid")
                .setRangingEnabled(true).build();
        int clientId = doConnect();
        IWifiAwareDiscoverySessionCallback mockCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);

        mDut.publish(mPackageName, mFeatureId, clientId, publishConfig, mockCallback, mExtras);
    }

    /**
     * Validate that publish() verifies the input PublishConfig and fails on an invalid service
     * name.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testPublishInvalidServiceName() {
        doBadPublishConfiguration("Including invalid characters - spaces", null, null);
    }

    /**
     * Validate that publish() verifies the input PublishConfig and fails on a "very long"
     * service name.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testPublishServiceNameTooLong() {
        byte[] byteArray = new byte[MAX_LENGTH + 1];
        for (int i = 0; i < MAX_LENGTH + 1; ++i) {
            byteArray[i] = 'a';
        }
        doBadPublishConfiguration(new String(byteArray), null, null);
    }

    /**
     * Validate that publish() verifies the input PublishConfig and fails on a "very long" ssi.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testPublishSsiTooLong() {
        doBadPublishConfiguration("correctservicename", new byte[MAX_LENGTH + 1], null);
    }

    /**
     * Validate that publish() verifies the input PublishConfig and fails on a "very long" match
     * filter.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testPublishMatchFilterTooLong() {
        doBadPublishConfiguration("correctservicename", null, new byte[MAX_LENGTH + 1]);
    }

    /**
     * Validate that publish() verifies the input PublishConfig and fails on a bad match filter -
     * invalid LV.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testPublishMatchFilterBadLv() {
        byte[] badLv = { 0, 1, 127, 2, 126, 125, 3 };
        doBadPublishConfiguration("correctservicename", null, badLv);
    }

    /**
     * Validate updatePublish() - correct pass-through args.
     */
    @Test
    public void testUpdatePublish() {
        int sessionId = 1232;
        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName("something.valid")
                .build();
        int clientId = doConnect();

        mDut.updatePublish(clientId, sessionId, publishConfig);

        verify(mAwareStateManagerMock).updatePublish(clientId, sessionId, publishConfig);
    }

    /**
     * Validate updatePublish() error checking.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testUpdatePublishInvalid() {
        int sessionId = 1232;
        PublishConfig publishConfig = new PublishConfig.Builder()
                .setServiceName("something with spaces").build();
        int clientId = doConnect();

        mDut.updatePublish(clientId, sessionId, publishConfig);

        verify(mAwareStateManagerMock).updatePublish(clientId, sessionId, publishConfig);
    }

    /**
     * Validate subscribe() - correct pass-through args.
     */
    @Test
    public void testSubscribe() {
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder()
                .setServiceName("something.valid").setMaxDistanceMm(100).build();
        int clientId = doConnect();
        IWifiAwareDiscoverySessionCallback mockCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);

        mDut.subscribe(mPackageName, mFeatureId, clientId, subscribeConfig, mockCallback, mExtras);

        verify(mAwareStateManagerMock).subscribe(clientId, subscribeConfig, mockCallback);
    }

    private void setTargetSdkToT() {
        when(mWifiPermissionsUtil.isTargetSdkLessThan(any(),
                eq(Build.VERSION_CODES.TIRAMISU), anyInt())).thenReturn(false);
    }

    @Test
    public void testSubscribePostT() {
        assumeTrue(SdkLevel.isAtLeastT());
        setTargetSdkToT();

        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder()
                .setServiceName("something.valid").setMaxDistanceMm(100).build();
        int clientId = doConnect();
        IWifiAwareDiscoverySessionCallback mockCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);

        mDut.subscribe(mPackageName, mFeatureId, clientId, subscribeConfig, mockCallback, mExtras);

        verify(mAwareStateManagerMock).subscribe(clientId, subscribeConfig, mockCallback);
        verify(mWifiPermissionsUtil, never()).enforceLocationPermission(any(), any(), anyInt());
        verify(mWifiPermissionsUtil).enforceNearbyDevicesPermission(any(), eq(true), any());
    }

    @Test(expected = SecurityException.class)
    public void testSubscribeExceptionPostT() {
        assumeTrue(SdkLevel.isAtLeastT());
        setTargetSdkToT();
        // app has no nearby permission
        doThrow(new SecurityException()).when(mWifiPermissionsUtil)
                .enforceNearbyDevicesPermission(any(), anyBoolean(), any());
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder()
                .setServiceName("something.valid").setMaxDistanceMm(100).build();
        int clientId = doConnect();
        IWifiAwareDiscoverySessionCallback mockCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);

        mDut.subscribe(mPackageName, mFeatureId, clientId, subscribeConfig, mockCallback, mExtras);
    }

    /**
     * Validate that subscribe() verifies the input SubscribeConfig and fails on an invalid service
     * name.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testSubscribeInvalidServiceName() {
        doBadSubscribeConfiguration("Including invalid characters - spaces", null, null);
    }

    /**
     * Validate that subscribe() verifies the input SubscribeConfig and fails on a "very long"
     * service name.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testSubscribeServiceNameTooLong() {
        byte[] byteArray = new byte[MAX_LENGTH + 1];
        for (int i = 0; i < MAX_LENGTH + 1; ++i) {
            byteArray[i] = 'a';
        }
        doBadSubscribeConfiguration(new String(byteArray), null, null);
    }

    /**
     * Validate that subscribe() verifies the input SubscribeConfig and fails on a "very long" ssi.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testSubscribeSsiTooLong() {
        doBadSubscribeConfiguration("correctservicename", new byte[MAX_LENGTH + 1], null);
    }

    /**
     * Validate that subscribe() verifies the input SubscribeConfig and fails on a "very long" match
     * filter.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testSubscribeMatchFilterTooLong() {
        doBadSubscribeConfiguration("correctservicename", null, new byte[MAX_LENGTH + 1]);
    }

    /**
     * Validate that subscribe() verifies the input SubscribeConfig and fails on a bad match filter
     * - invalid LV.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testSubscribeMatchFilterBadLv() {
        byte[] badLv = { 0, 1, 127, 2, 126, 125, 3 };
        doBadSubscribeConfiguration("correctservicename", null, badLv);
    }

    /**
     * Validate updateSubscribe() error checking.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testUpdateSubscribeInvalid() {
        int sessionId = 1232;
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder()
                .setServiceName("something.valid")
                .setServiceSpecificInfo(new byte[MAX_LENGTH + 1]).build();
        int clientId = doConnect();

        mDut.updateSubscribe(clientId, sessionId, subscribeConfig);

        verify(mAwareStateManagerMock).updateSubscribe(clientId, sessionId, subscribeConfig);
    }

    /**
     * Validate updateSubscribe() validates configuration.
     */
    @Test
    public void testUpdateSubscribe() {
        int sessionId = 1232;
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder()
                .setServiceName("something.valid").build();
        int clientId = doConnect();

        mDut.updateSubscribe(clientId, sessionId, subscribeConfig);

        verify(mAwareStateManagerMock).updateSubscribe(clientId, sessionId, subscribeConfig);
    }

    /**
     * Validate sendMessage() - correct pass-through args.
     */
    @Test
    public void testSendMessage() {
        int sessionId = 2394;
        int peerId = 2032;
        byte[] message = new byte[MAX_LENGTH];
        int messageId = 2043;
        int clientId = doConnect();

        mDut.sendMessage(clientId, sessionId, peerId, message, messageId, 0);

        verify(mAwareStateManagerMock).sendMessage(anyInt(), eq(clientId), eq(sessionId),
                eq(peerId), eq(message), eq(messageId), eq(0));
    }

    /**
     * Validate sendMessage() validates that message length is correct.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testSendMessageTooLong() {
        int sessionId = 2394;
        int peerId = 2032;
        byte[] message = new byte[MAX_LENGTH + 1];
        int messageId = 2043;
        int clientId = doConnect();

        mDut.sendMessage(clientId, sessionId, peerId, message, messageId, 0);

        verify(mAwareStateManagerMock).sendMessage(anyInt(), eq(clientId), eq(sessionId),
                eq(peerId), eq(message), eq(messageId), eq(0));
    }

    @Test
    public void testRequestMacAddress() {
        int uid = 1005;
        int[] peerIdArray = new int[0];
        IWifiAwareMacAddressProvider callback = new IWifiAwareMacAddressProvider() { // placeholder
            @Override
            public void macAddress(MacAddrMapping[] peerIdToMacList) throws RemoteException {
                // empty
            }

            @Override
            public IBinder asBinder() {
                return null;
            }
        };

        mDut.requestMacAddresses(uid, peerIdArray, callback);

        verify(mAwareStateManagerMock).requestMacAddresses(uid, peerIdArray, callback);
    }

    @Test(expected = SecurityException.class)
    public void testRequestMacAddressWithoutPermission() {
        doThrow(new SecurityException()).when(mContextMock).enforceCallingOrSelfPermission(
                eq(Manifest.permission.NETWORK_STACK), anyString());

        mDut.requestMacAddresses(1005, new int[0], new IWifiAwareMacAddressProvider() {
            @Override
            public void macAddress(MacAddrMapping[] peerIdToMacList) throws RemoteException {
                // empty
            }

            @Override
            public IBinder asBinder() {
                return null;
            }
        });
    }

    @Test
    public void testCapabilityTranslation() {
        final int maxServiceName = 66;
        final int maxServiceSpecificInfo = 69;
        final int maxMatchFilter = 55;

        Capabilities cap = new Capabilities();
        cap.maxConcurrentAwareClusters = 1;
        cap.maxPublishes = 2;
        cap.maxSubscribes = 2;
        cap.maxServiceNameLen = maxServiceName;
        cap.maxMatchFilterLen = maxMatchFilter;
        cap.maxTotalMatchFilterLen = 255;
        cap.maxServiceSpecificInfoLen = maxServiceSpecificInfo;
        cap.maxExtendedServiceSpecificInfoLen = MAX_LENGTH;
        cap.maxNdiInterfaces = 1;
        cap.maxNdpSessions = 1;
        cap.maxAppInfoLen = 255;
        cap.maxQueuedTransmitMessages = 6;
        cap.supportedCipherSuites = Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_256;
        cap.isInstantCommunicationModeSupported = true;

        Characteristics characteristics = cap.toPublicCharacteristics();
        assertEquals(characteristics.getMaxServiceNameLength(), maxServiceName);
        assertEquals(characteristics.getMaxServiceSpecificInfoLength(), maxServiceSpecificInfo);
        assertEquals(characteristics.getMaxMatchFilterLength(), maxMatchFilter);
        assertEquals(characteristics.getSupportedCipherSuites(),
                Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_256);
        assertEquals(characteristics.getNumberOfSupportedDataPaths(), 1);
        assertEquals(characteristics.getNumberOfSupportedDataInterfaces(), 1);
        assertEquals(characteristics.getNumberOfSupportedPublishSessions(), 2);
        assertEquals(characteristics.getNumberOfSupportedSubscribeSessions(), 2);
        if (SdkLevel.isAtLeastS()) {
            assertEquals(characteristics.isInstantCommunicationModeSupported(), true);
        }
    }

    @Test
    public void testPublishWifiValidSecurityConfig() {
        WifiAwareDataPathSecurityConfig securityConfig = new WifiAwareDataPathSecurityConfig
                .Builder(Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_256)
                .setPskPassphrase("somePassphrase").build();
        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName("something.valid")
                .setDataPathSecurityConfig(securityConfig)
                .setRangingEnabled(true).build();
        int clientId = doConnect();
        IWifiAwareDiscoverySessionCallback mockCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);

        mDut.publish(mPackageName, mFeatureId, clientId, publishConfig, mockCallback, mExtras);

        verify(mAwareStateManagerMock).publish(clientId, publishConfig, mockCallback);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPublishWifiInvalidSecurityConfig() {
        WifiAwareDataPathSecurityConfig securityConfig = new WifiAwareDataPathSecurityConfig
                .Builder(Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_128)
                .setPskPassphrase("somePassphrase").build();
        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName("something.valid")
                .setDataPathSecurityConfig(securityConfig)
                .setRangingEnabled(true).build();
        int clientId = doConnect();
        IWifiAwareDiscoverySessionCallback mockCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);

        mDut.publish(mPackageName, mFeatureId, clientId, publishConfig, mockCallback, mExtras);
    }

    /*
     * Utilities
     */

    /*
     * Tests of internal state of WifiAwareServiceImpl: very limited (not usually
     * a good idea). However, these test that the internal state is cleaned-up
     * appropriately. Alternatively would cause issues with memory leaks or
     * information leak between sessions.
     */
    private void validateInternalStateCleanedUp(int clientId) throws Exception {
        int uidEntry = getInternalStateUid(clientId);
        assertEquals(-1, uidEntry);

        IBinder.DeathRecipient dr = getInternalStateDeathRecipient(clientId);
        assertEquals(null, dr);
    }

    private void doBadPublishConfiguration(String serviceName, byte[] ssi, byte[] matchFilter)
            throws IllegalArgumentException {
        // using the hidden constructor since may be passing invalid parameters which would be
        // caught by the Builder. Want to test whether service side will catch invalidly
        // constructed configs.
        PublishConfig publishConfig = new PublishConfig(serviceName.getBytes(), ssi, matchFilter,
                PublishConfig.PUBLISH_TYPE_UNSOLICITED, 0, true, false, false,
                WifiScanner.WIFI_BAND_24_GHZ, null);
        int clientId = doConnect();
        IWifiAwareDiscoverySessionCallback mockCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);

        mDut.publish(mPackageName, mFeatureId, clientId, publishConfig, mockCallback, mExtras);

        verify(mAwareStateManagerMock).publish(clientId, publishConfig, mockCallback);
    }

    private void doBadSubscribeConfiguration(String serviceName, byte[] ssi, byte[] matchFilter)
            throws IllegalArgumentException {
        // using the hidden constructor since may be passing invalid parameters which would be
        // caught by the Builder. Want to test whether service side will catch invalidly
        // constructed configs.
        SubscribeConfig subscribeConfig = new SubscribeConfig(serviceName.getBytes(), ssi,
                matchFilter, SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE, 0, true, false, 0, false, 0,
                false, WifiScanner.WIFI_BAND_24_GHZ);
        int clientId = doConnect();
        IWifiAwareDiscoverySessionCallback mockCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);

        mDut.subscribe(mPackageName, mFeatureId, clientId, subscribeConfig, mockCallback, mExtras);

        verify(mAwareStateManagerMock).subscribe(clientId, subscribeConfig, mockCallback);
    }

    private int doConnect() {
        String callingPackage = "com.google.somePackage";
        String callingFeatureId = "com.google.someFeature";

        mDut.connect(mBinderMock, callingPackage, callingFeatureId, mCallbackMock, null, false,
                mExtras);

        ArgumentCaptor<Integer> clientId = ArgumentCaptor.forClass(Integer.class);
        verify(mAwareStateManagerMock).connect(clientId.capture(), anyInt(), anyInt(),
                eq(callingPackage), eq(callingFeatureId), eq(mCallbackMock),
                eq(new ConfigRequest.Builder().build()), eq(false), any());

        return clientId.getValue();
    }

    private static Characteristics getCharacteristics() {
        Capabilities cap = new Capabilities();
        cap.maxConcurrentAwareClusters = 1;
        cap.maxPublishes = 2;
        cap.maxSubscribes = 2;
        cap.maxServiceNameLen = MAX_LENGTH;
        cap.maxMatchFilterLen = MAX_LENGTH;
        cap.maxTotalMatchFilterLen = 255;
        cap.maxServiceSpecificInfoLen = MAX_LENGTH;
        cap.maxExtendedServiceSpecificInfoLen = MAX_LENGTH;
        cap.maxNdiInterfaces = 1;
        cap.maxNdpSessions = 1;
        cap.maxAppInfoLen = 255;
        cap.maxQueuedTransmitMessages = 6;
        cap.supportedCipherSuites = Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_256;
        cap.isInstantCommunicationModeSupported = false;
        return cap.toPublicCharacteristics();
    }

    private int getInternalStateUid(int clientId) throws Exception {
        Field field = WifiAwareServiceImpl.class.getDeclaredField("mUidByClientId");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        SparseIntArray uidByClientId = (SparseIntArray) field.get(mDut);

        return uidByClientId.get(clientId, -1);
    }

    private IBinder.DeathRecipient getInternalStateDeathRecipient(int clientId) throws Exception {
        Field field = WifiAwareServiceImpl.class.getDeclaredField("mDeathRecipientsByClientId");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        SparseArray<IBinder.DeathRecipient> deathRecipientsByClientId =
                            (SparseArray<IBinder.DeathRecipient>) field.get(mDut);

        return deathRecipientsByClientId.get(clientId);
    }
}
