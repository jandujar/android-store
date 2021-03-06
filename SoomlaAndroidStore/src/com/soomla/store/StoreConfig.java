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

/**
 * This class holds the store's configurations.
 */
public class StoreConfig {

    // do you want to print out debug messages?
    public static boolean debug = false;

    // the obfuscated salt. randomly generated numbers.
    public static byte[] obfuscationSalt = new byte[] { 64, -54, -113, -47, 98, -52, 87, -102, -65, -127, 89, 51, -11, -35, 30, 77, -45, 75, -26, 3 };

    // the Google Market public key
    public static String publicKey = "WILL BE CHANGED WHEN YOU INITIALIZE StoreController !";

    /*
    your personal secret that'll be used to encrypt data.

    CHANGE THE VALUE OF THIS SECRET NOW !
    */
    public static String customSecret = "ChangeMe!!!";

    /*
    if the value of this variable is true, metadata (or more specifically your IStoreAssets) that was previously
    saved in the local DB will be deleted every time you start the application.

    another way to delete just the METADATA table is to upgrade the database version.
     */
    public static final boolean DB_VOLATILE_METADATA = true;

    /*
    if this is true than the database will be deleted whenever the application loads.
    don't release your game with this option set to true !!!!!!!!!!!!
    otherwise, your users will lose all their data every time they load the application.

    NOTE: this feature can be useful on testing when you want to change stuff in your implementation of IStoreAssets
        and see how they look like. If you try to change things in IStoreAssets and don't delete the DB than your
        changes will not be shown.
     */
    public static final boolean DB_DELETE = false;

    /*
    this variable determines if the values in the database should be encrypted or not.
    if you change this value to "false", anyone will be able to browse your sqlite file
    and change the values of the currencies and balances.

    SOOMLA RECOMMENDS YOU TO ***NEVER*** CHANGE THE VALUE FOR THIS VARIABLE !!!
    (the only possible reason for you to want to even think of an insecure database is for debugging purposes)
     */
    public static final boolean DB_SECURE = true;

}
