/*
 * Child Growth Monitor - quick and accurate data on malnutrition
 * Copyright (c) 2018 Markus Matiaschek <mmatiaschek@gmail.com> for Welthungerhilfe
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package de.welthungerhilfe.cgm.scanner.helper;

/**
 * Created by Emerald on 2/19/2018.
 */

public class AppConstants {

    public static final int MAX_IMAGE_SIZE = 512;

    public static final long SYNC_INTERVAL = 60 * 5;
    public static final long SYNC_FLEXTIME = SYNC_INTERVAL;
    public static final long MEMORY_MONITOR_INTERVAL = 60 * 1000; // 60 seconds
    public static final long HEALTH_INTERVAL = 60 * 60 * 1000; // 1 hour

    public static final int MULTI_UPLOAD_BUNCH = 5;

    public static final String LOCAL_CONSENT_URL = "/{qrcode}/consent/{scantimestamp}/consent/";
    public static final String STORAGE_CONSENT_URL = "qrcode/{qrcode}/consent/{scantimestamp}/";
    public static final String STORAGE_CALIBRATION_URL = "qrcode/{qrcode}/measure/{scantimestamp}/";
    public static final String STORAGE_DEPTH_URL = "qrcode/{qrcode}/measure/{scantimestamp}/depth/";
    public static final String STORAGE_RGB_URL = "qrcode/{qrcode}/measure/{scantimestamp}/rgb/";

    public static final String VAL_SEX_FEMALE = "female";
    public static final String VAL_SEX_MALE = "male";

    public static final String VAL_MEASURE_MANUAL = "manual";
    public static final String VAL_MEASURE_AUTO = "v0.6";

    public static final String LANG_ENGLISH = "en";
    public static final String LANG_GERMAN = "de";
    public static final String LANG_HINDI = "hi";

    public static final String[] SUPPORTED_LANGUAGES = {
            LANG_ENGLISH,
            LANG_GERMAN,
            LANG_HINDI
    };

    public static final String EXTRA_QR = "extra_qr";
    public static final String EXTRA_QR_BITMAP = "extra_qr_bitmap";
    public static final String EXTRA_QR_URL = "extra_qr_url";
    public static final String EXTRA_RADIUS = "extra_radius";
    public static final String EXTRA_PERSON = "extra_person";
    public static final String EXTRA_MEASURE = "extra_measure";
    public static final String EXTRA_TUTORIAL_AGAIN = "extra_tutorial_again";

    public static final String PARAM_AUTHTOKEN_TYPE = "authtoken_type";
    public static final String PARAM_AUTH_NAME = "auth_name";
    public static final String PARAM_AUTH_CONFIRM = "auth_confirm";

    public static final String AUTHTOKEN_TYPE = "de.welthungerhilfe.cgm.scanner";
    public static final String ACCOUNT_TYPE = "de.welthungerhilfe.cgm.scanner";

    public static final int SCAN_STANDING = 0x00;
    public static final int SCAN_LYING = 0x01;

    public static final int SCAN_PREVIEW = 0;
    public static final int SCAN_STANDING_FRONT = 100;
    public static final int SCAN_STANDING_SIDE = 101;
    public static final int SCAN_STANDING_BACK = 102;
    public static final int SCAN_LYING_FRONT = 200;
    public static final int SCAN_LYING_SIDE = 201;
    public static final int SCAN_LYING_BACK = 202;

    public static final int UPLOADED = 201;
    public static final int UPLOADED_DELETED = 202;
    public static final int UPLOAD_ERROR = 400;
    public static final int FILE_NOT_FOUND = 404;

    public static final int SORT_DATE = 0;
    public static final int SORT_LOCATION = 1;
    public static final int SORT_WASTING = 2;
    public static final int SORT_STUNTING = 3;

    public static final int MIN_CONFIDENCE = 100;
}
