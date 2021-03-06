/*
 * Copyright (C) 2012 Soomla Inc.
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
package com.soomla.store;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.util.Log;
import com.soomla.billing.BillingService;
import com.soomla.billing.Consts;
import com.soomla.billing.PurchaseObserver;
import com.soomla.billing.ResponseHandler;
import com.soomla.store.data.StorageManager;
import com.soomla.store.data.StoreInfo;
import com.soomla.store.domain.data.GoogleMarketItem;
import com.soomla.store.domain.data.VirtualCurrency;
import com.soomla.store.domain.data.VirtualCurrencyPack;
import com.soomla.store.domain.data.VirtualGood;
import com.soomla.store.exceptions.InsufficientFundsException;
import com.soomla.store.exceptions.NotEnoughGoodsException;
import com.soomla.store.exceptions.VirtualItemNotFoundException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This class is where all the important stuff happens. You can use it to purchase products from Google Play,
 * buy virtual goods, and get events on whatever happens.
 *
 * This is the only class you need to initialize in order to use the SOOMLA SDK.
 *
 * In addition to initializing this class, you'll also have to call
 * {@link StoreController#storeOpening(android.app.Activity, android.os.Handler)} and
 * {@link com.soomla.store.StoreController#storeClosing()} when you open the store window or close it. These two
 * calls initializes important components that support billing and storage information (see implementation below).
 * IMPORTANT: if you use the SOOMLA storefront (SOOMLA Storefront), than DON'T call these 2 functions.
 *
 */
public class StoreController extends PurchaseObserver {

    /**
     * If you're using SOOMLA's storefront, You have to initializePurchaseObserver the {@link StoreController} before you
     * open the StorefrontController (for more info about store front go to our Github page).
     * This initializer also initializes {@link StorageManager} and {@link StoreInfo}.
     * @param context is used to initializePurchaseObserver {@link StorageManager}
     * @param storeAssets is the definition of your application specific assets.
     * @param publicKey is your public key from Google Play.
     * @param debugMode is determining weather you're on debug mode or not (duh !!!).
     */
    public void initialize(Context context,
                           IStoreAssets storeAssets,
                           String publicKey,
                           boolean debugMode){

        if (mInitialized){
            return;
        }

        if (publicKey == null || publicKey.isEmpty()) {
            Log.e(TAG, "publicKey is null or empty. can't initialize store !!");
        }

        mInitialized = true;

        mContext = context;
        StoreConfig.publicKey = publicKey;
        StoreConfig.debug = debugMode;

        StorageManager.getInstance().initialize(context);
        StoreInfo.getInstance().initialize(storeAssets);

        ResponseHandler.register(this);

        if (startBillingService(context)) {
            tryRestoreTransactions();
        }
    }

    /**
     * Start a currency pack purchase process (with Google Play)
     * @param productId is the product id of the required currency pack.
     */
    public void buyCurrencyPack(String productId) throws VirtualItemNotFoundException{
        try {
            StoreEventHandlers.getInstance().onMarketPurchaseProcessStarted(StoreInfo.getInstance().getPackByGoogleProductId(productId).getmGoogleItem());
            if (!mBillingService.requestPurchase(productId, Consts.ITEM_TYPE_INAPP, "")){
                StoreEventHandlers.getInstance().onUnexpectedErrorInStore();
            }
        } catch (VirtualItemNotFoundException e) {
            Log.e(TAG, "The currency pack associated with the given productId must be defined in your IStoreAssets " +
                    "(and thus must exist in StoreInfo. (productId: " + productId + "). Unexpected error is emitted.");
            throw e;
        }
    }

    /**
     * Start a virtual goods purchase process.
     * @param itemId is the item id of the required virtual good.
     * @throws InsufficientFundsException
     * @throws VirtualItemNotFoundException
     */
    public void buyVirtualGood(String itemId) throws InsufficientFundsException, VirtualItemNotFoundException{
        StoreEventHandlers.getInstance().onGoodsPurchaseProcessStarted();
        VirtualGood good = StoreInfo.getInstance().getVirtualGoodByItemId(itemId);

        // fetching currencies and amounts that the user needs in order to purchase the current
        // {@link VirtualGood}.
        HashMap<String, Integer> currencyValues = good.getCurrencyValues();

        // preparing list of {@link VirtualCurrency} objects.
        List<VirtualCurrency> virtualCurrencies = new ArrayList<VirtualCurrency>();
        for (String currencyItemId : currencyValues.keySet()){
            virtualCurrencies.add(StoreInfo.getInstance().getVirtualCurrencyByItemId(currencyItemId));
        }

        // checking if the user has enough of each of the virtual currencies in order to purchase this virtual
        // good.
        VirtualCurrency needMore = null;
        for (VirtualCurrency virtualCurrency : virtualCurrencies){
            int currencyBalance = StorageManager.getInstance().getVirtualCurrencyStorage().getBalance
                    (virtualCurrency);
            int currencyBalanceNeeded = currencyValues.get(virtualCurrency.getItemId());
            if (currencyBalance < currencyBalanceNeeded){
                needMore = virtualCurrency;
                break;
            }
        }

        // if the user has enough, the virtual good is purchased.
        if (needMore == null){
            StorageManager.getInstance().getVirtualGoodsStorage().add(good, 1);
            for (VirtualCurrency virtualCurrency : virtualCurrencies){
                int currencyBalanceNeeded = currencyValues.get(virtualCurrency.getItemId());
                StorageManager.getInstance().getVirtualCurrencyStorage().remove(virtualCurrency,
                        currencyBalanceNeeded);
            }

            StoreEventHandlers.getInstance().onVirtualGoodPurchased(good);
        }
        else {
            throw new InsufficientFundsException(needMore.getItemId());
        }
    }

    /**
     * Start a MANAGED item purchase process.
     * @param productId is the product id of the MANAGED item to purchase.
     */
    public void buyManagedItem(String productId) throws VirtualItemNotFoundException{
        try {
            GoogleMarketItem googleMarketItem = StoreInfo.getInstance().getGoogleManagedItemByProductId(productId);

            StoreEventHandlers.getInstance().onMarketPurchaseProcessStarted(googleMarketItem);
            if (!mBillingService.requestPurchase(productId, Consts.ITEM_TYPE_INAPP, "")){
                StoreEventHandlers.getInstance().onUnexpectedErrorInStore();
            }
        } catch (VirtualItemNotFoundException e) {
            Log.e(TAG, "The google market (MANAGED) item associated with the given productId must be defined in your IStoreAssets " +
                    "and thus must exist in StoreInfo. (productId: " + productId + "). Unexpected error is emitted. can't continue purchase !");
            throw e;
        }
    }

    /**
     * Make a VirtualGood equipped by the user.
     * @param itemId is the item id of the required virtual good.
     * @throws NotEnoughGoodsException
     * @throws VirtualItemNotFoundException
     */
    public void equipVirtualGood(String itemId) throws NotEnoughGoodsException, VirtualItemNotFoundException{
        VirtualGood good = StoreInfo.getInstance().getVirtualGoodByItemId(itemId);

        // if the user has enough, the virtual good is purchased.
        if (StorageManager.getInstance().getVirtualGoodsStorage().getBalance(good) > 0){
            StorageManager.getInstance().getVirtualGoodsStorage().equip(good, true);

            StoreEventHandlers.getInstance().onVirtualGoodEquipped(good);
        }
        else {
            throw new NotEnoughGoodsException(itemId);
        }
    }

    /**
     * Make a VirtualGood unequipped by the user.
     * @param itemId is the item id of the required virtual good.
     * @throws VirtualItemNotFoundException
     */
    public void unequipVirtualGood(String itemId) throws VirtualItemNotFoundException{
        VirtualGood good = StoreInfo.getInstance().getVirtualGoodByItemId(itemId);

        StorageManager.getInstance().getVirtualGoodsStorage().equip(good, false);

        StoreEventHandlers.getInstance().onVirtualGoodUnequipped(good);
    }

    /**
     * Call this function when you open the actual store window
     * @param activity is the activity being opened (or the activity that contains the store)/
     * @param handler is a handler to post UI thread messages on.
     */
    public void storeOpening(Activity activity, Handler handler){
        if (!mInitialized || mStoreOpen) {
            Log.e(TAG, "You notified StoreController that your store is opening but StoreController was never initialized." +
                    "REMEMBER: You should only initialize StoreController ONCE when the application loads !!!");
            return;
        }

        mStoreOpen = true;

        if (handler == null) {
            handler = new Handler();
        }
        initCompatibilityLayer(activity, handler);

        /* Initialize StoreInfo from database in case any changes were done to it while the store was closed */
        StoreInfo.getInstance().initializeFromDB();

        /* Billing */
        startBillingService(activity.getApplicationContext());

        StoreEventHandlers.getInstance().onOpeningStore();
    }

    /**
     * Call this function when you close the actual store window.
     */
    public void storeClosing(){
        mStoreOpen = false;

        StoreEventHandlers.getInstance().onClosingStore();

        stopBillingService();
//        ResponseHandler.unregister(this);
    }


    /** PurchaseObserver overridden functions**/

    /**
     * docs in {@link PurchaseObserver#onBillingSupported(boolean supported, String type)}.
     */
    @Override
    public void onBillingSupported(boolean supported, String type) {
        if (type == null || type.equals(Consts.ITEM_TYPE_INAPP)) {
            if (supported) {
                if (StoreConfig.debug){
                    Log.d(TAG, "billing is supported !");
                }
                StoreEventHandlers.getInstance().onBillingSupported();
            } else {
                // purchase is not supported. just send a message to JS to disable the "get more ..." button.

                if (StoreConfig.debug){
                    Log.d(TAG, "billing is not supported !");
                }

                StoreEventHandlers.getInstance().onBillingNotSupported();
            }
        } else if (type.equals(Consts.ITEM_TYPE_SUBSCRIPTION)) {
            // subscription is not supported
            // Soomla doesn't support subscriptions yet. doing nothing here ...
        } else {
            // subscription is not supported
            // Soomla doesn't support subscriptions yet. doing nothing here ...
        }
    }

    /**
     * docs in {@link PurchaseObserver#onPurchaseStateChange(com.soomla.billing.Consts.PurchaseState, String, long, String)}.
     */
    @Override
    public void onPurchaseStateChange(Consts.PurchaseState purchaseState, String productId, long purchaseTime, String developerPayload) {
        GoogleMarketItem googleMarketItem = null;
        try {

            VirtualCurrencyPack pack = StoreInfo.getInstance().getPackByGoogleProductId(productId);
            googleMarketItem = pack.getmGoogleItem();

            // updating the currency balance
            if (purchaseState == Consts.PurchaseState.PURCHASED) {
                StorageManager.getInstance().getVirtualCurrencyStorage().add(
                        pack.getVirtualCurrency(), pack.getCurrencyAmount());
            }

            if (purchaseState == Consts.PurchaseState.REFUNDED){
                // You can decrease the balance here ... SOOMLA believes in friendly refunds.
                // A friendly refund policy is nicer for the user.
            }

        } catch (VirtualItemNotFoundException e) {

            try {
                googleMarketItem = StoreInfo.getInstance().getGoogleManagedItemByProductId(productId);

                // updating the MANAGED item
                if (purchaseState == Consts.PurchaseState.PURCHASED) {
                    StorageManager.getInstance().getGoogleManagedItemsStorage().add(googleMarketItem);
                }

                if (purchaseState == Consts.PurchaseState.REFUNDED){
                    // You can remove the MANAGED item here ... SOOMLA believes in friendly refunds.
                    // A friendly refund policy is nice for the user.
                }

            } catch (VirtualItemNotFoundException e1) {
                Log.e(TAG, "ERROR : Couldn't find the " + purchaseState.name() +
                        " VirtualCurrencyPack OR GoogleMarketItem  with productId: " + productId +
                        ". It's unexpected so an unexpected error is being emitted.");
                StoreEventHandlers.getInstance().onUnexpectedErrorInStore();
            }
        }

        if (googleMarketItem == null){
            return;
        }

        // here we just post the appropriate event.
        if (purchaseState == Consts.PurchaseState.PURCHASED) {
            StoreEventHandlers.getInstance().onMarketPurchase(googleMarketItem);
        }

        if (purchaseState == Consts.PurchaseState.REFUNDED){
            StoreEventHandlers.getInstance().onMarketRefund(googleMarketItem);
        }
    }

    /**
     * docs in {@link PurchaseObserver#onRequestPurchaseResponse(com.soomla.billing.BillingService.RequestPurchase, com.soomla.billing.Consts.ResponseCode)}.
     */
    @Override
    public void onRequestPurchaseResponse(BillingService.RequestPurchase request, Consts.ResponseCode responseCode) {
        if (responseCode == Consts.ResponseCode.RESULT_OK) {
            // purchase was sent to server
        } else if (responseCode == Consts.ResponseCode.RESULT_USER_CANCELED) {

            // purchase canceled by user... doing nothing for now.

        } else {
            // purchase failed !

            StoreEventHandlers.getInstance().onUnexpectedErrorInStore();
            Log.e(TAG, "ERROR : Purchase failed for productId: " + request.mProductId);
        }
    }

    /**
     * docs in {@link PurchaseObserver#onRestoreTransactionsResponse(com.soomla.billing.BillingService.RestoreTransactions, com.soomla.billing.Consts.ResponseCode)}.
     */
    @Override
    public void onRestoreTransactionsResponse(BillingService.RestoreTransactions request, Consts.ResponseCode responseCode) {

        if (responseCode == Consts.ResponseCode.RESULT_OK) {
            if (StoreConfig.debug){
                Log.d(TAG, "RestoreTransactions succeeded");
            }

            // Update the shared preferences so that we don't perform
            // a RestoreTransactions again.
            SharedPreferences prefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor edit = prefs.edit();
            edit.putBoolean(DB_INITIALIZED, true);
            edit.commit();
        } else {
            if (StoreConfig.debug) {
                Log.d(TAG, "RestoreTransactions error: " + responseCode);
            }
        }

        // we're stopping the billing service only if the store was not opened while the request was sent
        if (!mStoreOpen) {
            stopBillingService();
        }
    }


    /** Private methods **/

    private void tryRestoreTransactions() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean initialized = prefs.getBoolean(DB_INITIALIZED, false);
        if (!initialized) {
            if (StoreConfig.debug){
                Log.d(TAG, "sending restore transaction request");
            }
            mBillingService.restoreTransactions();
        }
    }

    private boolean startBillingService(Context context) {
        mLock.lock();
        if (mBillingService == null) {
            mBillingService = new BillingService();
            mBillingService.setContext(context);

            if (!mBillingService.checkBillingSupported(Consts.ITEM_TYPE_INAPP)){
                if (StoreConfig.debug){
                    Log.d(TAG, "There's no connectivity with the billing service.");
                }

                mLock.unlock();
                return false;
            }
        }

        mLock.unlock();
        return true;
    }

    private void stopBillingService() {
        mLock.lock();
        if (mBillingService != null) {
            mBillingService.unbind();
            mBillingService = null;
        }
        mLock.unlock();
    }

    /** Singleton **/

    private static StoreController sInstance = null;

    public static StoreController getInstance(){
        if (sInstance == null){
            sInstance = new StoreController();
        }

        return sInstance;
    }

    private StoreController() {
    }


    /** Private Members**/

    private static final String TAG             = "SOOMLA StoreController";
    private static final String PREFS_NAME      = "store.prefs";
    private static final String DB_INITIALIZED  = "db_initialized";

    private boolean mInitialized          = false;
    private boolean mStoreOpen            = false;

    private BillingService mBillingService;
    private Context mContext;
    private Lock    mLock = new ReentrantLock();
}
