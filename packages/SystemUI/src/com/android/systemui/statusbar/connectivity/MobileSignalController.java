/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.statusbar.connectivity;

import static com.android.settingslib.mobile.MobileMappings.getDefaultIcons;
import static com.android.settingslib.mobile.MobileMappings.getIconKey;
import static com.android.settingslib.mobile.MobileMappings.mapIconSets;

import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthCdma;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsRegistrationAttributes;
import android.telephony.ims.ImsStateCallback;
import android.telephony.ims.RegistrationManager.RegistrationCallback;
import android.telephony.ims.feature.MmTelFeature;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.SignalIcon.MobileIconGroup;
import com.android.settingslib.graph.SignalDrawable;
import com.android.settingslib.mobile.MobileMappings;
import com.android.settingslib.mobile.MobileMappings.Config;
import com.android.settingslib.mobile.MobileStatusTracker;
import com.android.settingslib.mobile.MobileStatusTracker.MobileStatus;
import com.android.settingslib.mobile.MobileStatusTracker.SubscriptionDefaults;
import com.android.settingslib.mobile.TelephonyIcons;
import com.android.settingslib.net.SignalStrengthUtil;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.CarrierConfigTracker;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

/**
 * Monitors the mobile signal changes and update the SysUI icons.
 */
public class MobileSignalController extends SignalController<MobileState, MobileIconGroup>
        implements TunerService.Tunable {

    private static final SimpleDateFormat SSDF = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");
    private static final int STATUS_HISTORY_SIZE = 64;

    private static final String ROAMING_INDICATOR_ICON =
            "system:" + Settings.System.ROAMING_INDICATOR_ICON;
    private static final String SHOW_FOURG_ICON =
            "system:" + Settings.System.SHOW_FOURG_ICON;
    private static final String DATA_DISABLED_ICON =
            "system:" + Settings.System.DATA_DISABLED_ICON;

    private final TelephonyManager mPhone;
    private final CarrierConfigTracker mCarrierConfigTracker;
    private final ImsMmTelManager mImsMmTelManager;
    private final SubscriptionDefaults mDefaults;
    private final String mNetworkNameDefault;
    private final String mNetworkNameSeparator;
    private final ContentObserver mObserver;
    private final Handler mReceiverHandler;
    // Save entire info for logging, we only use the id.
    final SubscriptionInfo mSubscriptionInfo;
    private Map<String, MobileIconGroup> mNetworkToIconLookup;

    private MobileIconGroup mDefaultIcons;
    private Config mConfig;
    @VisibleForTesting
    boolean mInflateSignalStrengths = false;
    @VisibleForTesting
    final MobileStatusTracker mMobileStatusTracker;

    // Save the previous STATUS_HISTORY_SIZE states for logging.
    private final String[] mMobileStatusHistory = new String[STATUS_HISTORY_SIZE];
    // Where to copy the next state into.
    private int mMobileStatusHistoryIndex;

    private boolean mIsVowifiAvailable;
    private boolean mRoamingIconAllowed;
    private boolean mDataDisabledIcon;

    private final MobileStatusTracker.Callback mMobileCallback =
            new MobileStatusTracker.Callback() {
                private String mLastStatus;

                @Override
                public void onMobileStatusChanged(boolean updateTelephony,
                        MobileStatus mobileStatus) {
                    if (Log.isLoggable(mTag, Log.DEBUG)) {
                        Log.d(mTag, "onMobileStatusChanged="
                                + " updateTelephony=" + updateTelephony
                                + " mobileStatus=" + mobileStatus.toString());
                    }
                    String currentStatus = mobileStatus.toString();
                    if (!currentStatus.equals(mLastStatus)) {
                        mLastStatus = currentStatus;
                        String status = new StringBuilder()
                                .append(SSDF.format(System.currentTimeMillis())).append(",")
                                .append(currentStatus)
                                .toString();
                        recordLastMobileStatus(status);
                    }
                    updateMobileStatus(mobileStatus);
                    if (updateTelephony) {
                        updateTelephony();
                    } else {
                        notifyListenersIfNecessary();
                    }
                }
            };

    private final RegistrationCallback mRegistrationCallback = new RegistrationCallback() {
        @Override
        public void onRegistered(ImsRegistrationAttributes attributes) {
            Log.d(mTag, "onRegistered: " + "attributes=" + attributes);
            mCurrentState.imsRegistered = true;
            notifyListenersIfNecessary();
        }

        @Override
        public void onRegistering(ImsRegistrationAttributes attr) {
            mCurrentState.imsRegistered = false;
            notifyListenersIfNecessary();
        }

        @Override
        public void onUnregistered(ImsReasonInfo info) {
            Log.d(mTag, "onDeregistered: " + "info=" + info);
            mCurrentState.imsRegistered = false;
            notifyListenersIfNecessary();
        }
    };

    // TODO: Reduce number of vars passed in, if we have the NetworkController, probably don't
    // need listener lists anymore.
    public MobileSignalController(
            Context context,
            Config config,
            boolean hasMobileData,
            TelephonyManager phone,
            CallbackHandler callbackHandler,
            NetworkControllerImpl networkController,
            SubscriptionInfo info,
            SubscriptionDefaults defaults,
            Looper receiverLooper,
            CarrierConfigTracker carrierConfigTracker,
            MobileStatusTrackerFactory mobileStatusTrackerFactory
    ) {
        super("MobileSignalController(" + info.getSubscriptionId() + ")", context,
                NetworkCapabilities.TRANSPORT_CELLULAR, callbackHandler,
                networkController);
        mCarrierConfigTracker = carrierConfigTracker;
        mConfig = config;
        mPhone = phone;
        mDefaults = defaults;
        mSubscriptionInfo = info;
        mNetworkNameSeparator = getTextIfExists(
                R.string.status_bar_network_name_separator).toString();
        mNetworkNameDefault = getTextIfExists(
                com.android.internal.R.string.lockscreen_carrier_default).toString();
        mReceiverHandler = new Handler(receiverLooper);

        mNetworkToIconLookup = mapIconSets(mConfig);
        mDefaultIcons = getDefaultIcons(mConfig);

        String networkName = info.getCarrierName() != null ? info.getCarrierName().toString()
                : mNetworkNameDefault;
        mLastState.networkName = mCurrentState.networkName = networkName;
        mLastState.networkNameData = mCurrentState.networkNameData = networkName;
        mLastState.enabled = mCurrentState.enabled = hasMobileData;
        mLastState.iconGroup = mCurrentState.iconGroup = mDefaultIcons;
        mObserver = new ContentObserver(new Handler(receiverLooper)) {
            @Override
            public void onChange(boolean selfChange) {
                updateTelephony();
            }
        };

        mImsMmTelManager = ImsMmTelManager.createForSubscriptionId(info.getSubscriptionId());
        mMobileStatusTracker = mobileStatusTrackerFactory.createTracker(mMobileCallback);

        Dependency.get(TunerService.class).addTunable(this, ROAMING_INDICATOR_ICON);
        Dependency.get(TunerService.class).addTunable(this, SHOW_FOURG_ICON);
        Dependency.get(TunerService.class).addTunable(this, DATA_DISABLED_ICON);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case ROAMING_INDICATOR_ICON:
                mRoamingIconAllowed =
                    TunerService.parseIntegerSwitch(newValue, true);
                updateTelephony();
                break;
            case SHOW_FOURG_ICON:
                mConfig = Config.readConfig(mContext);
                setConfiguration(mConfig);
                notifyListeners();
                break;
            case DATA_DISABLED_ICON:
                mDataDisabledIcon = 
                    TunerService.parseIntegerSwitch(newValue, true);
                updateTelephony();
                break; 
            default:
                break;
        }
    }

    void setConfiguration(Config config) {
        mConfig = config;
        updateInflateSignalStrength();
        mNetworkToIconLookup = mapIconSets(mConfig);
        mDefaultIcons = getDefaultIcons(mConfig);
        updateTelephony();
    }

    void setAirplaneMode(boolean airplaneMode) {
        mCurrentState.airplaneMode = airplaneMode;
        notifyListenersIfNecessary();
    }

    void setUserSetupComplete(boolean userSetup) {
        mCurrentState.userSetup = userSetup;
        notifyListenersIfNecessary();
    }

    @Override
    public void updateConnectivity(BitSet connectedTransports, BitSet validatedTransports) {
        boolean isValidated = validatedTransports.get(mTransportType);
        mCurrentState.isDefault = connectedTransports.get(mTransportType);
        // Only show this as not having connectivity if we are default.
        mCurrentState.inetCondition = (isValidated || !mCurrentState.isDefault) ? 1 : 0;
        notifyListenersIfNecessary();
    }

    @Override
    void notifyListenersIfNecessary() {
        super.notifyListenersIfNecessary();
        mNetworkController.updateImsIcon();
    }

    void setCarrierNetworkChangeMode(boolean carrierNetworkChangeMode) {
        mCurrentState.carrierNetworkChangeMode = carrierNetworkChangeMode;
        updateTelephony();
    }

    /**
     * Start listening for phone state changes.
     */
    public void registerListener() {
        mMobileStatusTracker.setListening(true);
        mContext.getContentResolver().registerContentObserver(Global.getUriFor(Global.MOBILE_DATA),
                true, mObserver);
        mContext.getContentResolver().registerContentObserver(Global.getUriFor(
                Global.MOBILE_DATA + mSubscriptionInfo.getSubscriptionId()),
                true, mObserver);
        mReceiverHandler.post(mTryRegisterIms);
        try {
            mImsMmTelManager.registerImsStateCallback(mContext.getMainExecutor(),
                    mImsStateCallback);
        } catch (ImsException exception) {
            Log.e(mTag, "failed to call registerImsStateCallback ", exception);
        }
    }

    // There is no listener to monitor whether the IMS service is ready, so we have to retry the
    // IMS registration.
    private final Runnable mTryRegisterIms = new Runnable() {
        private static final int MAX_RETRY = 12;
        private int mRetryCount;

        @Override
        public void run() {
            try {
                mRetryCount++;
                mImsMmTelManager.registerImsRegistrationCallback(
                        mReceiverHandler::post, mRegistrationCallback);
                Log.d(mTag, "registerImsRegistrationCallback succeeded");
                queryImsState();
            } catch (RuntimeException | ImsException e) {
                if (mRetryCount < MAX_RETRY) {
                    Log.e(mTag, mRetryCount + " registerImsRegistrationCallback failed", e);
                    // Wait for 5 seconds to retry
                    mReceiverHandler.postDelayed(mTryRegisterIms, 5000);
                }
            }
        }
    };

    /**
     * Stop listening for phone state changes.
     */
    public void unregisterListener() {
        mMobileStatusTracker.setListening(false);
        mContext.getContentResolver().unregisterContentObserver(mObserver);
        try {
            mImsMmTelManager.unregisterImsRegistrationCallback(mRegistrationCallback);
        } catch (Exception e){
            Log.e(mTag, "unregisterListener: fail to call unregisterImsRegistrationCallback", e);
        }
        mImsMmTelManager.unregisterImsStateCallback(mImsStateCallback);
    }

    private void updateInflateSignalStrength() {
        mInflateSignalStrengths = SignalStrengthUtil.shouldInflateSignalStrength(mContext,
                mSubscriptionInfo.getSubscriptionId());
    }

    private int getNumLevels() {
        if (mInflateSignalStrengths) {
            return CellSignalStrength.getNumSignalStrengthLevels() + 1;
        }
        return CellSignalStrength.getNumSignalStrengthLevels();
    }

    @Override
    public int getCurrentIconId() {
        if (mCurrentState.iconGroup == TelephonyIcons.CARRIER_NETWORK_CHANGE) {
            return SignalDrawable.getCarrierChangeState(getNumLevels());
        } else if (mCurrentState.connected) {
            int level = mCurrentState.level;
            if (mInflateSignalStrengths) {
                level++;
            }
            boolean dataDisabled = mCurrentState.userSetup
                    && (mCurrentState.iconGroup == TelephonyIcons.DATA_DISABLED
                    || (mCurrentState.iconGroup == TelephonyIcons.NOT_DEFAULT_DATA
                            && mCurrentState.defaultDataOff));
            boolean noInternet = mCurrentState.inetCondition == 0;
            boolean cutOut = dataDisabled || noInternet;
            return SignalDrawable.getState(level, getNumLevels(), cutOut);
        } else if (mCurrentState.enabled) {
            return SignalDrawable.getEmptyState(getNumLevels());
        } else {
            return 0;
        }
    }

    @Override
    public int getQsCurrentIconId() {
        return getCurrentIconId();
    }

    private int getVolteResId() {
        int resId = 0;

        if ((mCurrentState.voiceCapable || mCurrentState.videoCapable)
                && mCurrentState.imsRegistered) {
            resId = R.drawable.ic_volte;
        }
        return resId;
    }

    private void setListeners() {
        try {
            Log.d(mTag, "setListeners: register CapabilitiesCallback and RegistrationCallback");
            mImsMmTelManager.registerMmTelCapabilityCallback(mContext.getMainExecutor(),
                    mCapabilityCallback);
            mImsMmTelManager.registerImsRegistrationCallback(mContext.getMainExecutor(),
                    mRegistrationCallback);
        } catch (ImsException e) {
            Log.e(mTag, "unable to register listeners.", e);
        }
        queryImsState();
    }

    private void queryImsState() {
        TelephonyManager tm = mPhone.createForSubscriptionId(mSubscriptionInfo.getSubscriptionId());
        mCurrentState.voiceCapable = tm.isVolteAvailable();
        mCurrentState.videoCapable = tm.isVideoTelephonyAvailable();
        mCurrentState.imsRegistered = mPhone.isImsRegistered(mSubscriptionInfo.getSubscriptionId());
        mIsVowifiAvailable = tm.isWifiCallingAvailable();
        if (DEBUG) {
            Log.d(mTag, "queryImsState tm=" + tm + " phone=" + mPhone
                    + " voiceCapable=" + mCurrentState.voiceCapable
                    + " videoCapable=" + mCurrentState.videoCapable
                    + " imsRegistered=" + mCurrentState.imsRegistered
                    + " mIsVowifiAvailable=" + mIsVowifiAvailable);
        }
        notifyListenersIfNecessary();
    }

    private void removeListeners() {
        try {
            Log.d(mTag,
                    "removeListeners: unregister CapabilitiesCallback and RegistrationCallback");
            mImsMmTelManager.unregisterMmTelCapabilityCallback(mCapabilityCallback);
            mImsMmTelManager.unregisterImsRegistrationCallback(mRegistrationCallback);
        } catch (Exception e) {
            Log.e(mTag, "removeListeners", e);
        }
        queryImsState();
    }

    @Override
    public void notifyListeners(SignalCallback callback) {
        // If the device is on carrier merged WiFi, we should let WifiSignalController to control
        // the SysUI states.
        if (mNetworkController.isCarrierMergedWifi(mSubscriptionInfo.getSubscriptionId())) {
            return;
        }
        MobileIconGroup icons = getIcons();

        String contentDescription = getTextIfExists(getContentDescription()).toString();
        CharSequence dataContentDescriptionHtml = getTextIfExists(icons.dataContentDescription);

        //TODO: Hacky
        // The data content description can sometimes be shown in a text view and might come to us
        // as HTML. Strip any styling here so that listeners don't have to care
        CharSequence dataContentDescription = Html.fromHtml(
                dataContentDescriptionHtml.toString(), 0).toString();
        if (mCurrentState.inetCondition == 0) {
            dataContentDescription = mContext.getString(R.string.data_connection_no_internet);
        }

        final QsInfo qsInfo = getQsInfo(contentDescription, icons.dataType);
        final SbInfo sbInfo = getSbInfo(contentDescription, icons.dataType);

        MobileDataIndicators mobileDataIndicators = new MobileDataIndicators(
                sbInfo.icon,
                qsInfo.icon,
                sbInfo.ratTypeIcon,
                qsInfo.ratTypeIcon,
                mCurrentState.hasActivityIn(),
                mCurrentState.hasActivityOut(),
                dataContentDescription,
                dataContentDescriptionHtml,
                qsInfo.description,
                mSubscriptionInfo.getSubscriptionId(),
                mCurrentState.roaming,
                sbInfo.showTriangle,
                mCurrentState.isDefault);
        callback.setMobileDataIndicators(mobileDataIndicators);
    }

    private QsInfo getQsInfo(String contentDescription, int dataTypeIcon) {
        int qsTypeIcon = 0;
        IconState qsIcon = null;
        CharSequence qsDescription = null;

        if (mCurrentState.dataSim) {
            if (mCurrentState.showQuickSettingsRatIcon() || mConfig.alwaysShowDataRatIcon) {
                qsTypeIcon = dataTypeIcon;
            }

            boolean qsIconVisible = mCurrentState.enabled && !mCurrentState.isEmergency;
            qsIcon = new IconState(qsIconVisible, getQsCurrentIconId(), contentDescription);

            if (!mCurrentState.isEmergency) {
                qsDescription = mCurrentState.networkName;
            }
        }

        return new QsInfo(qsTypeIcon, qsIcon, qsDescription);
    }

    private SbInfo getSbInfo(String contentDescription, int dataTypeIcon) {
        final boolean dataDisabled = mCurrentState.isDataDisabledOrNotDefault();
        IconState statusIcon = new IconState(
                mCurrentState.enabled && !mCurrentState.airplaneMode,
                getCurrentIconId(), contentDescription);

        boolean showDataIconInStatusBar =
                (mCurrentState.dataConnected && mCurrentState.isDefault) || dataDisabled;
        int typeIcon =
                (showDataIconInStatusBar || mConfig.alwaysShowDataRatIcon) ? dataTypeIcon : 0;
        boolean showTriangle = mCurrentState.enabled && !mCurrentState.airplaneMode;

        return new SbInfo(showTriangle, typeIcon, statusIcon);
    }

    public boolean isVolteAvailable() {
        return mCurrentState.imsRegistered
                   && (mCurrentState.voiceCapable || mCurrentState.videoCapable);
    }

    @Override
    protected MobileState cleanState() {
        return new MobileState();
    }

    public boolean isInService() {
        return mCurrentState.isInService();
    }

    String getNetworkNameForCarrierWiFi() {
        return mPhone.getSimOperatorName();
    }

    private boolean isRoaming() {
        // During a carrier change, roaming indications need to be suppressed.
        if (isCarrierNetworkChangeActive()) {
            return false;
        }
        if (mCurrentState.isCdma()) {
            return mPhone.getCdmaEnhancedRoamingIndicatorDisplayNumber()
                    != TelephonyManager.ERI_OFF;
        } else {
            return mCurrentState.isRoaming();
        }
    }

    private boolean isCarrierNetworkChangeActive() {
        return mCurrentState.carrierNetworkChangeMode;
    }

    void handleBroadcast(Intent intent) {
        String action = intent.getAction();
        if (action.equals(TelephonyManager.ACTION_SERVICE_PROVIDERS_UPDATED)) {
            updateNetworkName(intent.getBooleanExtra(TelephonyManager.EXTRA_SHOW_SPN, false),
                    intent.getStringExtra(TelephonyManager.EXTRA_SPN),
                    intent.getStringExtra(TelephonyManager.EXTRA_DATA_SPN),
                    intent.getBooleanExtra(TelephonyManager.EXTRA_SHOW_PLMN, false),
                    intent.getStringExtra(TelephonyManager.EXTRA_PLMN));
            notifyListenersIfNecessary();
        } else if (action.equals(TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED)) {
            updateDataSim();
            notifyListenersIfNecessary();
        }
    }

    private void updateDataSim() {
        int activeDataSubId = mDefaults.getActiveDataSubId();
        if (SubscriptionManager.isValidSubscriptionId(activeDataSubId)) {
            mCurrentState.dataSim = activeDataSubId == mSubscriptionInfo.getSubscriptionId();
        } else {
            // There doesn't seem to be a data sim selected, however if
            // there isn't a MobileSignalController with dataSim set, then
            // QS won't get any callbacks and will be blank.  Instead
            // lets just assume we are the data sim (which will basically
            // show one at random) in QS until one is selected.  The user
            // should pick one soon after, so we shouldn't be in this state
            // for long.
            mCurrentState.dataSim = true;
        }
    }

    /**
     * Updates the network's name based on incoming spn and plmn.
     */
    void updateNetworkName(boolean showSpn, String spn, String dataSpn,
            boolean showPlmn, String plmn) {
        if (CHATTY) {
            Log.d("CarrierLabel", "updateNetworkName showSpn=" + showSpn
                    + " spn=" + spn + " dataSpn=" + dataSpn
                    + " showPlmn=" + showPlmn + " plmn=" + plmn);
        }
        StringBuilder str = new StringBuilder();
        StringBuilder strData = new StringBuilder();
        if (showPlmn && plmn != null) {
            str.append(plmn);
            strData.append(plmn);
        }
        if (showSpn && spn != null && !str.toString().equalsIgnoreCase(spn)) {
            if (str.length() != 0) {
                str.append(mNetworkNameSeparator);
            }
            str.append(spn);
        }
        if (str.length() != 0) {
            mCurrentState.networkName = str.toString();
        } else {
            mCurrentState.networkName = mNetworkNameDefault;
        }
        if (showSpn && dataSpn != null && !strData.toString().equalsIgnoreCase(dataSpn)) {
            if (strData.length() != 0) {
                strData.append(mNetworkNameSeparator);
            }
            strData.append(dataSpn);
        }
        if (strData.length() != 0) {
            mCurrentState.networkNameData = strData.toString();
        } else {
            mCurrentState.networkNameData = mNetworkNameDefault;
        }
    }

    /**
     * Extracts the CellSignalStrengthCdma from SignalStrength then returns the level
     */
    private int getCdmaLevel(SignalStrength signalStrength) {
        List<CellSignalStrengthCdma> signalStrengthCdma =
                signalStrength.getCellSignalStrengths(CellSignalStrengthCdma.class);
        if (!signalStrengthCdma.isEmpty()) {
            return signalStrengthCdma.get(0).getLevel();
        }
        return CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
    }

    private void updateMobileStatus(MobileStatus mobileStatus) {
        mCurrentState.setFromMobileStatus(mobileStatus);
    }

    int getSignalLevel(SignalStrength signalStrength) {
        if (signalStrength == null) {
            return 0;
        }
        if (!signalStrength.isGsm() && mConfig.alwaysShowCdmaRssi) {
            return getCdmaLevel(signalStrength);
        } else {
            return signalStrength.getLevel();
        }
    }

    /**
     * Updates the current state based on ServiceState, SignalStrength, DataState,
     * TelephonyDisplayInfo, and sim state.  It should be called any time one of these is updated.
     * This will call listeners if necessary.
     */
    private void updateTelephony() {
        if (Log.isLoggable(mTag, Log.DEBUG)) {
            Log.d(mTag, "updateTelephonySignalStrength: hasService="
                    + mCurrentState.isInService()
                    + " ss=" + mCurrentState.signalStrength
                    + " displayInfo=" + mCurrentState.telephonyDisplayInfo);
        }
        checkDefaultData();
        mCurrentState.connected = mCurrentState.isInService();
        if (mCurrentState.connected) {
            mCurrentState.level = getSignalLevel(mCurrentState.signalStrength);
        }

        String iconKey = getIconKey(mCurrentState.telephonyDisplayInfo);
        if (mNetworkToIconLookup.get(iconKey) != null) {
            mCurrentState.iconGroup = mNetworkToIconLookup.get(iconKey);
        } else {
            mCurrentState.iconGroup = mDefaultIcons;
        }
        mCurrentState.dataConnected = mCurrentState.isDataConnected();

        mCurrentState.roaming = isRoaming() && mRoamingIconAllowed;
        if (isCarrierNetworkChangeActive()) {
            mCurrentState.iconGroup = TelephonyIcons.CARRIER_NETWORK_CHANGE;
        } else if (isDataDisabled() && mDataDisabledIcon) {
            if (mSubscriptionInfo.getSubscriptionId() != mDefaults.getDefaultDataSubId()) {
                mCurrentState.iconGroup = TelephonyIcons.NOT_DEFAULT_DATA;
            } else {
                mCurrentState.iconGroup = TelephonyIcons.DATA_DISABLED;
            }
        }
        if (mCurrentState.isEmergencyOnly() != mCurrentState.isEmergency) {
            mCurrentState.isEmergency = mCurrentState.isEmergencyOnly();
            mNetworkController.recalculateEmergency();
        }
        // Fill in the network name if we think we have it.
        if (mCurrentState.networkName.equals(mNetworkNameDefault)
                && !TextUtils.isEmpty(mCurrentState.getOperatorAlphaShort())) {
            mCurrentState.networkName = mCurrentState.getOperatorAlphaShort();
        }
        // If this is the data subscription, update the currentState data name
        if (mCurrentState.networkNameData.equals(mNetworkNameDefault)
                && mCurrentState.dataSim
                && !TextUtils.isEmpty(mCurrentState.getOperatorAlphaShort())) {
            mCurrentState.networkNameData = mCurrentState.getOperatorAlphaShort();
        }

        notifyListenersIfNecessary();
    }

    /**
     * If we are controlling the NOT_DEFAULT_DATA icon, check the status of the other one
     */
    private void checkDefaultData() {
        if (mCurrentState.iconGroup != TelephonyIcons.NOT_DEFAULT_DATA) {
            mCurrentState.defaultDataOff = false;
            return;
        }

        mCurrentState.defaultDataOff = mNetworkController.isDataControllerDisabled();
    }

    void onMobileDataChanged() {
        checkDefaultData();
        notifyListenersIfNecessary();
    }

    boolean isDataDisabled() {
        return !mPhone.isDataConnectionAllowed();
    }

    @VisibleForTesting
    void setActivity(int activity) {
        mCurrentState.activityIn = activity == TelephonyManager.DATA_ACTIVITY_INOUT
                || activity == TelephonyManager.DATA_ACTIVITY_IN;
        mCurrentState.activityOut = activity == TelephonyManager.DATA_ACTIVITY_INOUT
                || activity == TelephonyManager.DATA_ACTIVITY_OUT;
        notifyListenersIfNecessary();
    }

    private void recordLastMobileStatus(String mobileStatus) {
        mMobileStatusHistory[mMobileStatusHistoryIndex] = mobileStatus;
        mMobileStatusHistoryIndex = (mMobileStatusHistoryIndex + 1) % STATUS_HISTORY_SIZE;
    }

    @VisibleForTesting
    void setImsType(int imsType) {
        // Do nothing
    }

    private int getDataNetworkType() {
        return mCurrentState != null ?
                mCurrentState.getDataNetworkType() : TelephonyManager.NETWORK_TYPE_UNKNOWN;
    }

    public boolean isVowifiAvailable() {
        return mCurrentState.voiceCapable && mCurrentState.imsRegistered
                && (getDataNetworkType() == TelephonyManager.NETWORK_TYPE_IWLAN
                || mIsVowifiAvailable);
    }

    @Override
    public void dump(PrintWriter pw) {
        super.dump(pw);
        pw.println("  mSubscription=" + mSubscriptionInfo + ",");
        pw.println("  mInflateSignalStrengths=" + mInflateSignalStrengths + ",");
        pw.println("  isDataDisabled=" + isDataDisabled() + ",");
        pw.println("  mNetworkToIconLookup=" + mNetworkToIconLookup + ",");
        pw.println("  mMobileStatusTracker.isListening=" + mMobileStatusTracker.isListening());
        pw.println("  MobileStatusHistory");
        int size = 0;
        for (int i = 0; i < STATUS_HISTORY_SIZE; i++) {
            if (mMobileStatusHistory[i] != null) {
                size++;
            }
        }
        // Print out the previous states in ordered number.
        for (int i = mMobileStatusHistoryIndex + STATUS_HISTORY_SIZE - 1;
                i >= mMobileStatusHistoryIndex + STATUS_HISTORY_SIZE - size; i--) {
            pw.println("  Previous MobileStatus("
                    + (mMobileStatusHistoryIndex + STATUS_HISTORY_SIZE - i) + "): "
                    + mMobileStatusHistory[i & (STATUS_HISTORY_SIZE - 1)]);
        }

        dumpTableData(pw);
    }

    private ImsMmTelManager.CapabilityCallback mCapabilityCallback = new ImsMmTelManager.CapabilityCallback() {
        @Override
        public void onCapabilitiesStatusChanged(MmTelFeature.MmTelCapabilities config) {
            mCurrentState.voiceCapable =
                    config.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE);
            mCurrentState.videoCapable =
                    config.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VIDEO);
            Log.d(mTag, "onCapabilitiesStatusChanged isVoiceCapable=" + mCurrentState.voiceCapable
                    + " isVideoCapable=" + mCurrentState.videoCapable);
            notifyListenersIfNecessary();
        }
    };

    private final ImsStateCallback mImsStateCallback = new ImsStateCallback() {
        @Override
        public void onUnavailable(int reason) {
            Log.d(mTag, "ImsStateCallback.onUnavailable: reason=" + reason);
            removeListeners();
        }

        @Override
        public void onAvailable() {
            Log.d(mTag, "ImsStateCallback.onAvailable");
            setListeners();
        }

        @Override
        public void onError() {
            Log.e(mTag, "ImsStateCallback.onError");
            removeListeners();
        }
    };

    /** Box for QS icon info */
    private static final class QsInfo {
        final int ratTypeIcon;
        final IconState icon;
        final CharSequence description;

        QsInfo(int typeIcon, IconState iconState, CharSequence desc) {
            ratTypeIcon = typeIcon;
            icon = iconState;
            description = desc;
        }

        @Override
        public String toString() {
            return "QsInfo: ratTypeIcon=" + ratTypeIcon + " icon=" + icon;
        }
    }

    /** Box for status bar icon info */
    private static final class SbInfo {
        final boolean showTriangle;
        final int ratTypeIcon;
        final IconState icon;

        SbInfo(boolean show, int typeIcon, IconState iconState) {
            showTriangle = show;
            ratTypeIcon = typeIcon;
            icon = iconState;
        }

        @Override
        public String toString() {
            return "SbInfo: showTriangle=" + showTriangle + " ratTypeIcon=" + ratTypeIcon
                    + " icon=" + icon;
        }
    }
}
