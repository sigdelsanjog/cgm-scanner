/*
 * Child Growth Monitor - quick and accurate data on malnutrition
 * Copyright (c) 2018 Markus Matiaschek <mmatiaschek@gmail.com>
 * Copyright (c) 2018 Welthungerhilfe Innovation
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.welthungerhilfe.cgm.scanner.network.syncdata;

import static android.content.Context.TELEPHONY_SERVICE;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import de.welthungerhilfe.cgm.scanner.AppConstants;
import de.welthungerhilfe.cgm.scanner.AppController;
import de.welthungerhilfe.cgm.scanner.Utils;
import de.welthungerhilfe.cgm.scanner.datasource.database.CgmDatabase;
import de.welthungerhilfe.cgm.scanner.datasource.models.Artifact;
import de.welthungerhilfe.cgm.scanner.datasource.models.CompleteScan;
import de.welthungerhilfe.cgm.scanner.datasource.models.Consent;
import de.welthungerhilfe.cgm.scanner.datasource.models.Device;
import de.welthungerhilfe.cgm.scanner.datasource.models.FileLog;
import de.welthungerhilfe.cgm.scanner.datasource.models.Loc;
import de.welthungerhilfe.cgm.scanner.datasource.models.Measure;
import de.welthungerhilfe.cgm.scanner.datasource.models.Person;
import de.welthungerhilfe.cgm.scanner.datasource.models.PostScanResult;
import de.welthungerhilfe.cgm.scanner.datasource.models.ReceivedResult;
import de.welthungerhilfe.cgm.scanner.datasource.models.RemainingData;
import de.welthungerhilfe.cgm.scanner.datasource.models.ResultAppHeight;
import de.welthungerhilfe.cgm.scanner.datasource.models.ResultAppScore;
import de.welthungerhilfe.cgm.scanner.datasource.models.ResultAutoDetect;
import de.welthungerhilfe.cgm.scanner.datasource.models.ResultBoundingBox;
import de.welthungerhilfe.cgm.scanner.datasource.models.ResultChildDistance;
import de.welthungerhilfe.cgm.scanner.datasource.models.ResultLightScore;
import de.welthungerhilfe.cgm.scanner.datasource.models.ResultOrientation;
import de.welthungerhilfe.cgm.scanner.datasource.models.Results;
import de.welthungerhilfe.cgm.scanner.datasource.models.ResultsData;
import de.welthungerhilfe.cgm.scanner.datasource.models.Scan;
import de.welthungerhilfe.cgm.scanner.datasource.models.SyncPersonsResponse;
import de.welthungerhilfe.cgm.scanner.datasource.models.Workflow;
import de.welthungerhilfe.cgm.scanner.datasource.models.WorkflowsResponse;
import de.welthungerhilfe.cgm.scanner.datasource.repository.DeviceRepository;
import de.welthungerhilfe.cgm.scanner.datasource.repository.FileLogRepository;
import de.welthungerhilfe.cgm.scanner.datasource.repository.MeasureRepository;
import de.welthungerhilfe.cgm.scanner.datasource.repository.PersonRepository;
import de.welthungerhilfe.cgm.scanner.datasource.repository.PostScanResultrepository;
import de.welthungerhilfe.cgm.scanner.datasource.repository.WorkflowRepository;
import de.welthungerhilfe.cgm.scanner.hardware.io.LocalPersistency;
import de.welthungerhilfe.cgm.scanner.hardware.io.LogFileUtils;
import de.welthungerhilfe.cgm.scanner.network.NetworkUtils;
import de.welthungerhilfe.cgm.scanner.network.authenticator.AuthenticationHandler;
import de.welthungerhilfe.cgm.scanner.network.service.ApiService;
import de.welthungerhilfe.cgm.scanner.network.service.FirebaseService;
import de.welthungerhilfe.cgm.scanner.network.service.UploadService;
import de.welthungerhilfe.cgm.scanner.ui.activities.SettingsPerformanceActivity;
import de.welthungerhilfe.cgm.scanner.datasource.viewmodel.DataFormat;
import de.welthungerhilfe.cgm.scanner.hardware.io.SessionManager;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.Observer;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import okhttp3.RequestBody;
import retrofit2.Retrofit;


public class SyncAdapter implements FileLogRepository.OnFileLogsLoad {

    private static SyncAdapter instance;
    private static final Object lock = new Object();

    private final String TAG = SyncAdapter.class.getSimpleName();

    private Integer activeThreads;
    private boolean updated;
    private int updateDelay;

    private long prevTimestamp;
    private long currentTimestamp;

    private PersonRepository personRepository;
    private MeasureRepository measureRepository;
    private DeviceRepository deviceRepository;
    private FileLogRepository fileLogRepository;
    private PostScanResultrepository postScanResultrepository;
    private WorkflowRepository workflowRepository;

    private SessionManager session;
    private AsyncTask<Void, Void, Void> syncTask;
    private Retrofit retrofit;
    private Context context;
    FirebaseAnalytics firebaseAnalytics;
    private long lastSyncResultTimeStamp = 0L;
    private long lastSyncDailyReport = 0L;


    public SyncAdapter(Context context) {
        this.context = context;
        personRepository = PersonRepository.getInstance(context);
        measureRepository = MeasureRepository.getInstance(context);
        deviceRepository = DeviceRepository.getInstance(context);
        fileLogRepository = FileLogRepository.getInstance(context);
        postScanResultrepository = PostScanResultrepository.getInstance(context);
        firebaseAnalytics = FirebaseService.getFirebaseAnalyticsInstance(context);
        workflowRepository = WorkflowRepository.getInstance(context);
        activeThreads = 0;
        session = new SessionManager(context);
    }

    public static SyncAdapter getInstance(Context context) {
        if (instance == null) {
            instance = new SyncAdapter(context);
        }
        return instance;
    }

    public static Object getLock() {
        return lock;
    }

    public void resetRetrofit() {
        retrofit = null;
    }

    private void initUploadService() {
        if (!UploadService.isInitialized()) {
            try {
                context.startService(new Intent(context, UploadService.class));

            } catch (IllegalStateException e) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent intent = new Intent(context, UploadService.class);
                    intent.putExtra(AppConstants.IS_FOREGROUND, true);
                    context.startForegroundService(intent);
                }
            }
        } else {
            UploadService.forceResume();
        }
    }

    private synchronized void startSyncing() {
        LogFileUtils.logInfo(TAG, "Start syncing requested");

        if (!session.isSigned()) {
            return;
        }
        LogFileUtils.logInfo(TAG, "Start syncing requested after signed ");

        synchronized (activeThreads) {
            if (activeThreads > 0) {
                return;
            }
        }
        LogFileUtils.logInfo(TAG, "Start syncing requested after activeThreads 0");

        if (syncTask == null) {
            prevTimestamp = session.getSyncTimestamp();
            currentTimestamp = System.currentTimeMillis();
            LogFileUtils.logInfo(TAG, "Start syncing requested after synctask null");

            if (retrofit == null) {
                retrofit = SyncingWorkManager.provideRetrofit();
                LogFileUtils.logInfo(TAG, "Start syncing requested after retrofit null");

            }

            syncTask = new SyncTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            LogFileUtils.logInfo(TAG, "Start syncing started");
        }
        LogFileUtils.logInfo(TAG, "Start syncing started with synctask null");

    }


    public void startPeriodicSync() {
        initUploadService();
        startSyncing();
    }


    @SuppressLint("StaticFieldLeak")
    class SyncTask extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... voids) {
            if (!NetworkUtils.isUploadAllowed(context)) {
                LogFileUtils.logInfo(TAG, "skipped due to missing connection");
                syncTask = null;
                return null;
            }

            //REST API implementation
            updated = false;
            updateDelay = 60 * 60 * 1000;
            LogFileUtils.logInfo(TAG, "start updating");
            synchronized (getLock()) {
                try {
                  //  postRemainingData();
                    processPersonQueue();
                    processMeasureQueue();
                    processDeviceQueue();
                    processConsentSheet();
                    processMeasureResults();
                    getSyncPersons();
                    migrateEnvironmentColumns();
                    getWorkflows();
                    postWorkFlowsResult();
                    postRemainingData();

                    session.setSyncTimestamp(currentTimestamp);
                } catch (Exception e) {
                    LogFileUtils.logException(e);
                }
            }
            LogFileUtils.logInfo(TAG, "end updating");
            syncTask = null;
            onThreadChange(0);
            return null;
        }

        private void processMeasureResults() {

            try {
                List<Measure> measures = measureRepository.getMeasureWithoutScanResult();
                LogFileUtils.logInfo(TAG, measures.size() + " scan results to update");

                for (Measure measure : measures) {
                    getEstimates(measure);
                }
            } catch (Exception e) {
                currentTimestamp = prevTimestamp;
                LogFileUtils.logException(e);
            }

        }

        private void processPersonQueue() {
            try {
                List<Person> syncablePersons = personRepository.getSyncablePerson(session.getEnvironment());
                LogFileUtils.logInfo(TAG, syncablePersons.size() + " persons to sync");

                for (Person person : syncablePersons) {
                    if (person.getServerId() == null || person.getServerId().isEmpty()) {
                        postPerson(person);
                    } else {
                        putPerson(person);
                    }
                }
            } catch (Exception e) {
                currentTimestamp = prevTimestamp;
                LogFileUtils.logException(e);
            }
        }


        private void processMeasureQueue() {
            try {
                List<Measure> syncableMeasures = measureRepository.getSyncableMeasure(session.getEnvironment());
                LogFileUtils.logInfo(TAG, syncableMeasures.size() + " measures/scans to sync");

                for (Measure measure : syncableMeasures) {
                    String localPersonId = measure.getPersonId();
                    Person person = personRepository.getPersonById(localPersonId);
                    String backendPersonId = person.getServerId();
                    if (backendPersonId == null) {
                        try {
                            LogFileUtils.logInfo(TAG, "this is measures/scans to sync issue person id-> " + backendPersonId + " type -> " + measure.getType() + " measureid-> " + measure.getId() + " time-> " + DataFormat.convertMilliSeconsToServerDate(measure.getDate()) + " std code-> " + measure.getStd_test_qr_code());
                        }catch (Exception e){
                            LogFileUtils.logInfo(TAG, "this is measures/scans to sync issue person id missing ");
                        }
                        continue;
                    }

                    measure.setPersonServerKey(backendPersonId);
                    if (measure.getType().compareTo(AppConstants.VAL_MEASURE_MANUAL) == 0) {
                        if (measure.getMeasureServerKey() != null) {
                            putMeasurement(measure);
                        } else {
                            postMeasurement(measure);
                        }
                    } else {
                        try {
                            LogFileUtils.logInfo(TAG, "this is measures/scans to post measureid-> " + measure.getId() + " time-> " + DataFormat.convertMilliSeconsToServerDate(measure.getDate()) + " std code-> " + measure.getStd_test_qr_code());
                        }catch (Exception e){
                        }
                        HashMap<Integer, Scan> scans = measure.split(session, fileLogRepository, session.getEnvironment());

                        if (!scans.isEmpty()) {
                            postScans(scans, measure);
                        } else {
                            try {
                                LogFileUtils.logInfo(TAG, "this is measures/scans to sync issue scan empty person id-> " + backendPersonId + " type -> " + measure.getType() + " measureid-> " + measure.getId() + " time-> " + DataFormat.convertMilliSeconsToServerDate(measure.getDate()) + " std code-> " + measure.getStd_test_qr_code());
                                LogFileUtils.writeAppCenter(session,"SCAN_ERROR","this is measures/scans to sync issue scan empty");
                            }catch (Exception e){
                                LogFileUtils.logInfo(TAG, "this is measures/scans to sync issue scan empty");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                currentTimestamp = prevTimestamp;
                LogFileUtils.logException(e);
            }
        }

        private void processConsentSheet() {
            try {
                List<FileLog> syncableConsent = fileLogRepository.loadConsentFile(session.getEnvironment());
                LogFileUtils.logInfo(TAG, syncableConsent.size() + " consents to sync");

                for (FileLog fileLog : syncableConsent) {
                    if (!fileLog.isDeleted()) {
                        continue;
                    }
                    Person person = personRepository.findPersonByQr(fileLog.getQrCode(),session.getEnvironment());
                    LogFileUtils.logInfo(TAG,"Person consent to sync is "+person);
                    String backendPersonId = person.getServerId();
                    if (backendPersonId == null) {
                        continue;
                    }
                    postConsentSheet(fileLog, backendPersonId);
                }
            } catch (Exception e) {
                currentTimestamp = prevTimestamp;
                LogFileUtils.logException(e);
            }
        }


        private void processDeviceQueue() {
            try {
                List<Device> syncableDevices = deviceRepository.getSyncableDevice(prevTimestamp);
                LogFileUtils.logInfo(TAG, syncableDevices.size() + " devices to sync");

                for (Device device : syncableDevices) {

                    //TODO:process by REST API
                    device.setSync_timestamp(prevTimestamp);
                    deviceRepository.updateDevice(device);
                }
            } catch (Exception e) {
                currentTimestamp = prevTimestamp;
                LogFileUtils.logException(e);
            }
        }
    }

    private void postScans(HashMap<Integer, Scan> scans, Measure measure) {
        try {
            Gson gson = new GsonBuilder()
                    .excludeFieldsWithoutExposeAnnotation()
                    .create();

            final int[] count = {scans.values().size()};
            CompleteScan completeScan = new CompleteScan();
            ArrayList<Scan> scanList = new ArrayList();
            for (Scan scan : scans.values()) {
                scanList.add(scan);
                LogFileUtils.logInfo(TAG, "this is posting scan " + measure.getId());

            }
            completeScan.setScans(scanList);

            LogFileUtils.logInfo(TAG, "this is posting scan raw data " + (new JSONObject(gson.toJson(completeScan))).toString());

            onThreadChange(1);
                RequestBody body = RequestBody.create(okhttp3.MediaType.parse("application/json; charset=utf-8"), (new JSONObject(gson.toJson(completeScan))).toString());
                retrofit.create(ApiService.class).postScans(session.getAuthTokenWithBearer(), body).subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(new Observer<CompleteScan>() {
                            @Override
                            public void onSubscribe(@NonNull Disposable d) {

                            }

                            @Override
                            public void onNext(@NonNull CompleteScan completeScan) {

                                if(completeScan==null || completeScan.getScans().size() == 0){
                                    return;
                                }
                                try {
                                    LogFileUtils.logInfo(TAG,"this is value of response complete scan"+  (new JSONObject(gson.toJson(completeScan))).toString());
                                } catch (JSONException e) {
                                    e.printStackTrace();
                                }
                                for(Scan scan:completeScan.getScans()){
                                    LogFileUtils.logInfo(TAG, "scan " + measure.getId() + " successfully posted");
                                    PostScanResult postScanResult = new PostScanResult();
                                    postScanResult.setEnvironment(measure.getEnvironment());
                                    postScanResult.setId(scan.getId());
                                    postScanResult.setMeasure_id(measure.getId());
                                    postScanResult.setTimestamp(prevTimestamp);
                                    postScanResultrepository.insertPostScanResult(postScanResult);
                                    addScanDataToFileLogs(scan.getId(), scan.getArtifacts());
                                }

                                measure.setArtifact_synced(true);
                                measure.setTimestamp(session.getSyncTimestamp());
                                measure.setUploaded_at(System.currentTimeMillis());
                                measure.setSynced(true);
                                updated = true;
                                updateDelay = 0;
                                measureRepository.updateMeasure(measure);
                                onThreadChange(-1);
                            }

                            @Override
                            public void onError(@NonNull Throwable e) {
                                LogFileUtils.logError(TAG, "scan error  posting failed " + e.getMessage());
                                if (NetworkUtils.isExpiredToken(e.getMessage())) {
                                    AuthenticationHandler.restoreToken(context);
                                    error401();
                                }

                                onThreadChange(-1);
                            }

                            @Override
                            public void onComplete() {

                            }
                        });

        } catch (Exception e) {
            LogFileUtils.logException(e);
        }
    }

    /*private void postScans(HashMap<Integer, Scan> scans, Measure measure) {
        try {
            Gson gson = new GsonBuilder()
                    .excludeFieldsWithoutExposeAnnotation()
                    .create();

            final int[] count = {scans.values().size()};
            for (Scan scan : scans.values()) {

                onThreadChange(1);
                LogFileUtils.logInfo(TAG, "this is posting scan " + scan.getId());
                RequestBody body = RequestBody.create(okhttp3.MediaType.parse("application/json; charset=utf-8"), (new JSONObject(gson.toJson(scan))).toString());
                retrofit.create(ApiService.class).postScans(session.getAuthTokenWithBearer(), body).subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(new Observer<Scan>() {
                            @Override
                            public void onSubscribe(@NonNull Disposable d) {

                            }

                            @Override
                            public void onNext(@NonNull Scan scan) {
                                LogFileUtils.logInfo(TAG, "scan " + scan.getId() + " successfully posted");
                                PostScanResult postScanResult = new PostScanResult();
                                postScanResult.setEnvironment(measure.getEnvironment());
                                postScanResult.setId(scan.getId());
                                postScanResult.setMeasure_id(measure.getId());
                                postScanResult.setTimestamp(prevTimestamp);
                                postScanResultrepository.insertPostScanResult(postScanResult);
                                addScanDataToFileLogs(scan.getId(), scan.getArtifacts());

                                count[0]--;
                                if (count[0] == 0) {
                                    measure.setArtifact_synced(true);
                                    measure.setTimestamp(session.getSyncTimestamp());
                                    measure.setUploaded_at(System.currentTimeMillis());
                                    measure.setSynced(true);
                                    updated = true;
                                    updateDelay = 0;
                                    measureRepository.updateMeasure(measure);
                                }
                                onThreadChange(-1);
                            }

                            @Override
                            public void onError(@NonNull Throwable e) {
                                LogFileUtils.logError(TAG, "scan error " + scan.getId() + " posting failed " + e.getMessage());
                                if (NetworkUtils.isExpiredToken(e.getMessage())) {
                                    AuthenticationHandler.restoreToken(context);
                                    error401();
                                }

                                onThreadChange(-1);
                            }

                            @Override
                            public void onComplete() {

                            }
                        });
            }
        } catch (Exception e) {
            LogFileUtils.logException(e);
        }
    }*/

    public void addScanDataToFileLogs(String scanServerId, List<Artifact> artifactsList) {
        for (Artifact artifact : artifactsList) {
            FileLog fileLog = fileLogRepository.getFileLogByFileId(artifact.getFile());
            fileLog.setArtifactId(artifact.getId());
            fileLog.setScanServerId(scanServerId);
            fileLogRepository.updateFileLog(fileLog);
        }
    }


    public void postPerson(Person person1) {
        try {
            Gson gson = new GsonBuilder()
                    .excludeFieldsWithoutExposeAnnotation()
                    .create();

            person1.setBirthdayString(DataFormat.convertMilliSecondToBirthDay(person1.getBirthday()));
            person1.setQr_scanned(DataFormat.convertMilliSeconsToServerDate(person1.getCreated()));
            person1.setDevice_updated_at(DataFormat.convertMilliSeconsToServerDate(person1.getDevice_updated_at_timestamp()));

            RequestBody body = RequestBody.create(okhttp3.MediaType.parse("application/json; charset=utf-8"), (new JSONObject(gson.toJson(person1))).toString());

            onThreadChange(1);
            LogFileUtils.logInfo(TAG, "posting person " + person1.getQrcode());
            retrofit.create(ApiService.class).postPerson(session.getAuthTokenWithBearer(), body).subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Observer<Person>() {
                        @Override
                        public void onSubscribe(@NonNull Disposable d) {

                        }

                        @Override
                        public void onNext(@NonNull Person person) {
                            LogFileUtils.logInfo(TAG, "person " + person.getQrcode() + " successfully posted");
                            person.setTimestamp(prevTimestamp);
                            person.setId(person1.getId());
                            person.setCreatedBy(person1.getCreatedBy());
                            person.setCreated(person1.getCreated());
                            person.setSynced(true);
                            person.setEnvironment(person1.getEnvironment());
                            person.setDenied(false);
                            person.setDevice_updated_at_timestamp(DataFormat.convertServerDateToMilliSeconds(person1.getDevice_updated_at()));

                            Loc location = new Loc();
                            person.setLastLocation(location);
                            if (person1.getLastLocation() != null) {
                                person.getLastLocation().setAddress(person1.getLastLocation().getAddress());
                                person.getLastLocation().setLatitude(person1.getLastLocation().getLatitude());
                                person.getLastLocation().setLongitude(person1.getLastLocation().getLongitude());
                            }

                            person.setBirthday(person1.getBirthday());
                            personRepository.updatePerson(person);
                            updated = true;
                            updateDelay = 0;
                            onThreadChange(-1);
                        }

                        @Override
                        public void onError(@NonNull Throwable e) {
                            LogFileUtils.logError(TAG, "person " + person1.getQrcode() + " posting failed " + e.getMessage());
                            if (NetworkUtils.isDenied(e.getMessage())) {
                                person1.setDenied(true);
                                personRepository.updatePerson(person1);
                            }
                            if (NetworkUtils.isExpiredToken(e.getMessage())) {
                                AuthenticationHandler.restoreToken(context);
                                error401();
                            }
                            onThreadChange(-1);
                        }

                        @Override
                        public void onComplete() {

                        }
                    });
        } catch (Exception e) {
            LogFileUtils.logException(e);
        }
    }

    public void putPerson(Person person1) {
        try {

            Person putPerson = new Person();

            putPerson.setBirthdayString(DataFormat.convertMilliSecondToBirthDay(person1.getBirthday()));
            putPerson.setGuardian(person1.getGuardian());
            putPerson.setAgeEstimated(person1.isAgeEstimated());
            putPerson.setName(person1.getName());
            putPerson.setSex(person1.getSex());
            putPerson.setDevice_updated_at(DataFormat.convertMilliSeconsToServerDate(person1.getDevice_updated_at_timestamp()));


            Gson gson = new GsonBuilder()
                    .excludeFieldsWithoutExposeAnnotation()
                    .create();

            RequestBody body = RequestBody.create(okhttp3.MediaType.parse("application/json; charset=utf-8"), (new JSONObject(gson.toJson(putPerson))).toString());

            onThreadChange(1);
            LogFileUtils.logInfo(TAG, "putting person " + person1.getQrcode());
            retrofit.create(ApiService.class).putPerson(session.getAuthTokenWithBearer(), body, person1.getServerId()).subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Observer<Person>() {
                        @Override
                        public void onSubscribe(@NonNull Disposable d) {

                        }

                        @Override
                        public void onNext(@NonNull Person person) {
                            LogFileUtils.logInfo(TAG, "person " + person.getQrcode() + " successfully put");
                            person.setTimestamp(prevTimestamp);
                            person.setId(person1.getId());
                            person.setCreatedBy(person1.getCreatedBy());
                            person.setCreated(person1.getCreated());
                            person.setSynced(true);
                            person.setEnvironment(person1.getEnvironment());
                            person.setBirthday(person1.getBirthday());
                            person.setDevice_updated_at_timestamp(DataFormat.convertServerDateToMilliSeconds(person.getDevice_updated_at()));
                            personRepository.updatePerson(person);
                            updated = true;
                            updateDelay = 0;
                            onThreadChange(-1);
                        }

                        @Override
                        public void onError(@NonNull Throwable e) {
                            LogFileUtils.logError(TAG, "person " + person1.getQrcode() + " putting failed " + e.getMessage());
                            if (NetworkUtils.isExpiredToken(e.getMessage())) {
                                AuthenticationHandler.restoreToken(context);
                                error401();
                            }
                            onThreadChange(-1);
                        }

                        @Override
                        public void onComplete() {

                        }
                    });
        } catch (Exception e) {
            LogFileUtils.logException(e);
        }
    }

    public void getSyncPersons() {
        try {
            if ((System.currentTimeMillis() - session.getLastPersonSyncTimestamp()) < 10000L) {
                return;
            }

            String lastPersonSyncTime = null;
            if (session.getLastPersonSyncTimestamp() > 0) {
                lastPersonSyncTime = DataFormat.convertMilliSeconsToServerDate(session.getLastPersonSyncTimestamp());
                LogFileUtils.logInfo(TAG, "Syncing persons, the last sync was " + lastPersonSyncTime);
            } else {
                LogFileUtils.logInfo(TAG, "Syncing persons for the first time");
            }
            boolean belongs_to_rst;
            if(session.getSelectedMode()==AppConstants.CGM_MODE){
                belongs_to_rst = false;
            }
            else {
                belongs_to_rst = true;
            }
            LogFileUtils.logInfo(TAG, "Syncing persons "+belongs_to_rst);

            onThreadChange(1);
            retrofit.create(ApiService.class).getSyncPersons(session.getAuthTokenWithBearer(), lastPersonSyncTime, belongs_to_rst)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Observer<SyncPersonsResponse>() {
                        @Override
                        public void onSubscribe(@NonNull Disposable d) {

                        }

                        @Override
                        public void onNext(@NonNull SyncPersonsResponse syncPersonsResponse) {
                            LogFileUtils.logInfo(TAG, "Sync persons successfully fetch " + syncPersonsResponse.persons.size() + " person(s)");
                            session.setPersonSyncTimestamp(System.currentTimeMillis());
                            if (syncPersonsResponse.persons != null && syncPersonsResponse.persons.size() > 0) {
                                for (int i = 0; i < syncPersonsResponse.persons.size(); i++) {
                                    Person person = syncPersonsResponse.persons.get(i);
                                    Person existingPerson = personRepository.findPersonByQr(person.getQrcode(),person.getEnvironment());
                                    person.setSynced(true);
                                    person.setEnvironment(session.getEnvironment());
                                    person.setBirthday(DataFormat.convertBirthDateToMilliSeconds(person.getBirthdayString()));
                                    person.setCreated(DataFormat.convertServerDateToMilliSeconds(person.getQr_scanned()));
                                    person.setCreatedBy(session.getUserEmail());
                                    person.setDeleted(false);
                                    person.setSchema_version(CgmDatabase.version);
                                    person.setDevice_updated_at_timestamp(DataFormat.convertServerDateToMilliSeconds(person.getDevice_updated_at()));
                                    person.setLastLocation(null);

                                    if (existingPerson != null) {
                                        person.setId(existingPerson.getId());
                                        personRepository.updatePerson(person);

                                    } else {
                                        person.setId(AppController.getInstance().getPersonId());
                                        personRepository.insertPerson(person);
                                    }
                                }
                            }
                            updated = true;
                            updateDelay = 0;
                            onThreadChange(-1);
                        }

                        @Override
                        public void onError(@NonNull Throwable e) {
                            LogFileUtils.logError(TAG, "Sync person failed " + e.getMessage());
                            if (NetworkUtils.isExpiredToken(e.getMessage())) {
                                AuthenticationHandler.restoreToken(context);
                                error401();
                            }
                            onThreadChange(-1);
                        }

                        @Override
                        public void onComplete() {

                        }
                    });
        } catch (Exception e) {
            LogFileUtils.logException(e);
        }
    }


    public void postMeasurement(Measure measure) {
        try {
            Gson gson = new GsonBuilder()
                    .excludeFieldsWithoutExposeAnnotation()
                    .create();
            measure.setMeasured(DataFormat.convertMilliSeconsToServerDate(measure.getDate()));
            measure.setMeasure_updated(DataFormat.convertMilliSeconsToServerDate(measure.getTimestamp()));

            RequestBody body = RequestBody.create(okhttp3.MediaType.parse("application/json; charset=utf-8"), (new JSONObject(gson.toJson(measure))).toString());

            onThreadChange(1);
            LogFileUtils.logInfo(TAG, "posting measure " + measure.getId());
            retrofit.create(ApiService.class).postMeasure(session.getAuthTokenWithBearer(), body).subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Observer<Measure>() {
                        @Override
                        public void onSubscribe(@NonNull Disposable d) {

                        }

                        @Override
                        public void onNext(@NonNull Measure measure1) {
                            LogFileUtils.logInfo(TAG, "measure " + measure1.getId() + " successfully posted");
                            measure1.setTimestamp(DataFormat.convertServerDateToMilliSeconds(measure.getMeasure_updated()));
                            measure1.setId(measure.getId());
                            measure1.setPersonId(measure.getPersonId());
                            measure1.setType(AppConstants.VAL_MEASURE_MANUAL);
                            measure1.setCreatedBy(measure.getCreatedBy());
                            measure1.setDate(measure.getDate());
                            measure1.setUploaded_at(session.getSyncTimestamp());
                            measure1.setSynced(true);
                            measure1.setEnvironment(measure.getEnvironment());
                            measure1.setQrCode(measure.getQrCode());
                            measureRepository.updateMeasure(measure1);
                            updated = true;
                            updateDelay = 0;
                            onThreadChange(-1);
                        }

                        @Override
                        public void onError(@NonNull Throwable e) {
                            LogFileUtils.logError(TAG, "measure " + measure.getId() + " posting failed " + e.getMessage());
                            if (NetworkUtils.isExpiredToken(e.getMessage())) {
                                AuthenticationHandler.restoreToken(context);
                                error401();
                            }
                            onThreadChange(-1);
                        }

                        @Override
                        public void onComplete() {

                        }
                    });
        } catch (Exception e) {
            LogFileUtils.logException(e);
        }
    }

    public void putMeasurement(Measure measure) {
        try {
            Gson gson = new GsonBuilder()
                    .excludeFieldsWithoutExposeAnnotation()
                    .create();
            measure.setMeasured(DataFormat.convertMilliSeconsToServerDate(measure.getDate()));
            measure.setMeasure_updated(DataFormat.convertMilliSeconsToServerDate(measure.getTimestamp()));
            measure.setPersonServerKey(null);
            RequestBody body = RequestBody.create(okhttp3.MediaType.parse("application/json; charset=utf-8"), (new JSONObject(gson.toJson(measure))).toString());

            onThreadChange(1);
            LogFileUtils.logInfo(TAG, "putting measure " + measure.getId());
            retrofit.create(ApiService.class).putMeasure(session.getAuthTokenWithBearer(), body, measure.getMeasureServerKey()).subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Observer<Measure>() {
                        @Override
                        public void onSubscribe(@NonNull Disposable d) {

                        }

                        @Override
                        public void onNext(@NonNull Measure measure1) {
                            LogFileUtils.logInfo(TAG, "measure " + measure1.getId() + " successfully put");
                            measure1.setTimestamp(DataFormat.convertServerDateToMilliSeconds(measure.getMeasure_updated()));
                            measure1.setId(measure.getId());
                            measure1.setPersonId(measure.getPersonId());
                            measure1.setType(AppConstants.VAL_MEASURE_MANUAL);
                            measure1.setCreatedBy(measure.getCreatedBy());
                            measure1.setDate(measure.getDate());
                            measure1.setUploaded_at(session.getSyncTimestamp());
                            measure1.setEnvironment(measure.getEnvironment());
                            measure1.setSynced(true);
                            measure1.setQrCode(measure.getQrCode());
                            measureRepository.updateMeasure(measure1);
                            updated = true;
                            updateDelay = 0;
                            onThreadChange(-1);
                        }

                        @Override
                        public void onError(@NonNull Throwable e) {
                            LogFileUtils.logError(TAG, "measure " + measure.getId() + " putting failed " + e.getMessage());
                            if (NetworkUtils.isExpiredToken(e.getMessage())) {
                                AuthenticationHandler.restoreToken(context);
                                error401();
                            }
                            onThreadChange(-1);
                        }

                        @Override
                        public void onComplete() {

                        }
                    });
        } catch (Exception e) {
            LogFileUtils.logException(e);
        }
    }

    public void postConsentSheet(FileLog fileLog, String personId) {
        try {
            Gson gson = new GsonBuilder()
                    .excludeFieldsWithoutExposeAnnotation()
                    .create();

            Consent consent = new Consent();
            consent.setFile(fileLog.getServerId());
            consent.setScanned(DataFormat.convertMilliSeconsToServerDate(fileLog.getCreateDate()));

            RequestBody body = RequestBody.create(okhttp3.MediaType.parse("application/json; charset=utf-8"), (new JSONObject(gson.toJson(consent))).toString());

            onThreadChange(1);
            LogFileUtils.logInfo(TAG, "posting consent " + fileLog.getPath());
            retrofit.create(ApiService.class).postConsent(session.getAuthTokenWithBearer(), body, personId).subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Observer<Consent>() {
                        @Override
                        public void onSubscribe(@NonNull Disposable d) {

                        }

                        @Override
                        public void onNext(@NonNull Consent consent) {
                            LogFileUtils.logInfo(TAG, "consent " + fileLog.getPath() + " successfully posted");
                            fileLog.setStatus(AppConstants.CONSENT_UPLOADED);
                            fileLogRepository.updateFileLog(fileLog);
                            updated = true;
                            updateDelay = 0;
                            onThreadChange(-1);
                        }

                        @Override
                        public void onError(@NonNull Throwable e) {
                            LogFileUtils.logError(TAG, "consent " + fileLog.getPath() + " posting failed " + e.getMessage());
                            try {
                                LogFileUtils.logError(TAG,"consent request body "+new JSONObject(gson.toJson(consent)));
                            } catch (JSONException jsonException) {
                                jsonException.printStackTrace();
                            }
                            if (NetworkUtils.isExpiredToken(e.getMessage())) {
                                AuthenticationHandler.restoreToken(context);
                                error401();
                            }
                            onThreadChange(-1);
                        }

                        @Override
                        public void onComplete() {

                        }
                    });
        } catch (Exception e) {
            LogFileUtils.logException(e);
        }
    }

    public void getEstimates(Measure measure) {
        try {

            onThreadChange(1);
            LogFileUtils.logInfo(TAG, "getting estimate for scan result " + measure);
            List<String> postScanResultList = postScanResultrepository.getScanIdsFromMeasureId(measure.getId());

            String ids = postScanResultList.get(0) + "," + postScanResultList.get(1) + "," + postScanResultList.get(2);

            retrofit.create(ApiService.class).getEstimatesAll(session.getAuthTokenWithBearer(), ids).subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Observer<ReceivedResult>() {
                        @Override
                        public void onSubscribe(@NonNull Disposable d) {

                        }

                        @Override
                        public void onNext(@NonNull ReceivedResult receivedResult) {
                            //TODO : generate notification and store result based on confidence value
                            if (receivedResult != null && receivedResult.getEstimate()!=null && !receivedResult.getStatus().equalsIgnoreCase("result not generated") ) {
                                LogFileUtils.logInfo(TAG, "scan result " + measure.getId() + " estimate successfully received");

                                if (receivedResult.getEstimate().getMean_height() > 0) {

                                    String qrCode = getQrCode(measure.getId());
                                    MeasureNotification notification = MeasureNotification.get(qrCode);

                                    if (measure != null) {
                                        boolean hadHeight = measure.getHeightConfidence() > 0;
                                        measure.setHeight(receivedResult.getEstimate().getMean_height());
                                        measure.setHeightConfidence(0.1);
                                        measure.setResulted_at(System.currentTimeMillis());
                                        measure.setReceived_at(System.currentTimeMillis());
                                        if(receivedResult.getEstimate().getArtifact_max_99_percentile_pos_error()!=null) {
                                            measure.setPositive_height_error(Double.parseDouble(receivedResult.getEstimate().getArtifact_max_99_percentile_pos_error()));
                                        } else {
                                            measure.setPositive_height_error(0.0);
                                        }
                                        if(receivedResult.getEstimate().getArtifact_max_99_percentile_neg_error()!=null) {
                                            measure.setNegative_height_error(Double.parseDouble(receivedResult.getEstimate().getArtifact_max_99_percentile_neg_error()));
                                        } else {
                                            measure.setNegative_height_error(0.0);
                                        }
                                        measureRepository.updateMeasure(measure);


                                        if ((notification != null) && !hadHeight) {
                                            notification.setHeight((float) receivedResult.getEstimate().getMean_height());
                                            MeasureNotification.showNotification(context);
                                        }
                                    }
                                }

                                //Do not remove this code as we can use this chunk of code while app start receving weight result.
                               /* if (estimatesResponse.weight != null && estimatesResponse.weight.size() > 0) {
                                    Collections.sort(estimatesResponse.weight);
                                    float weight = estimatesResponse.weight.get((estimatesResponse.weight.size() / 2)).getValue();

                                    String qrCode = getQrCode(postScanResult.getMeasure_id());
                                    MeasureNotification notification = MeasureNotification.get(qrCode);

                                    if (measure != null) {
                                        boolean hadWeight = measure.getWeight() > 0;
                                        measure.setWeight(weight);
                                        measure.setWeightConfidence(0);
                                        measure.setResulted_at(postScanResult.getTimestamp());
                                        measure.setReceived_at(System.currentTimeMillis());
                                        measureRepository.updateMeasure(measure);

                                        if ((notification != null) && !hadWeight) {
                                            notification.setWeight(weight);
                                            MeasureNotification.showNotification(context);
                                        }
                                    }
                                }*/

                                if (measure != null) {
                                    if ((measure.getHeight() > 0)) {

                                        firebaseAnalytics.logEvent(FirebaseService.RESULT_RECEIVED, null);
                                        onResultReceived(measure.getId());
                                    }
                                }
                            } else if(receivedResult.getStatus().equalsIgnoreCase("result not generated")){
                                LogFileUtils.logInfo(TAG, "scan result " + measure.getId() + " estimate not generated yet");

                            }
                            updated = true;
                            updateDelay = Math.min(60 * 1000, updateDelay);
                            onThreadChange(-1);
                        }

                        @Override
                        public void onError(@NonNull Throwable e) {
                            LogFileUtils.logError(TAG, "scan result " + measure.getId() + " estimate receiving failed " + e.getMessage());
                            if (NetworkUtils.isExpiredToken(e.getMessage())) {
                                AuthenticationHandler.restoreToken(context);
                                error401();
                            }
                            onThreadChange(-1);
                        }

                        @Override
                        public void onComplete() {

                        }
                    });
        } catch (Exception e) {
            LogFileUtils.logException(e);
        }
    }


    private String getQrCode(String measureId) {
        try {
            return measureRepository.getMeasureById(measureId).getQrCode();
        } catch (Exception e) {
            LogFileUtils.logException(e);
        }
        return null;
    }

    private void onResultReceived(String measureId) {

        Context c = context;
        if (!LocalPersistency.getBoolean(c, SettingsPerformanceActivity.KEY_TEST_RESULT))
            return;
        if (LocalPersistency.getString(c, SettingsPerformanceActivity.KEY_TEST_RESULT_ID).compareTo(measureId) != 0)
            return;

        if (LocalPersistency.getLong(c, SettingsPerformanceActivity.KEY_TEST_RESULT_RECEIVE) == 0) {

            //set receive timestamp
            LocalPersistency.setLong(c, SettingsPerformanceActivity.KEY_TEST_RESULT_RECEIVE, System.currentTimeMillis());

            //update average time
            long diff = 0;
            diff += LocalPersistency.getLong(c, SettingsPerformanceActivity.KEY_TEST_RESULT_RECEIVE);
            diff -= LocalPersistency.getLong(c, SettingsPerformanceActivity.KEY_TEST_RESULT_SCAN);
            ArrayList<Long> last = LocalPersistency.getLongArray(c, SettingsPerformanceActivity.KEY_TEST_RESULT_AVERAGE);
            last.add(diff);
            if (last.size() > 10) {
                last.remove(0);
            }
            LocalPersistency.setLongArray(c, SettingsPerformanceActivity.KEY_TEST_RESULT_AVERAGE, last);
        }
    }

    private void onThreadChange(int diff) {
        int count;
        synchronized (activeThreads) {
            activeThreads += diff;
            count = activeThreads;
        }

        if (updated && (count == 0)) {
            new Thread(() -> {
                if (updateDelay > 0) {
                    AppController.sleep(updateDelay);
                }
                startSyncing();
            }).start();
        }
    }

    public void migrateEnvironmentColumns() {
        List<Person> syncablePersons = personRepository.getSyncablePerson(AppConstants.ENV_UNKNOWN);
        for (Person person : syncablePersons) {
            person.setEnvironment(session.getEnvironment());
            personRepository.updatePerson(person);
        }

        List<Measure> syncableMeasures = measureRepository.getSyncableMeasure(AppConstants.ENV_UNKNOWN);

        for (Measure measure : syncableMeasures) {
            measure.setEnvironment(session.getEnvironment());
            measureRepository.updateMeasure(measure);
        }

        List<PostScanResult> syncablePostScanResult = postScanResultrepository.getSyncablePostScanResult(AppConstants.ENV_UNKNOWN);

        for (PostScanResult scanResult : syncablePostScanResult) {
            scanResult.setEnvironment(session.getEnvironment());
            postScanResultrepository.updatePostScanResult(scanResult);
        }

        loadQueueFileLogs();
    }

    private void loadQueueFileLogs() {
        fileLogRepository.loadQueuedData(this, AppConstants.ENV_UNKNOWN);
    }

    @Override
    public void onFileLogsLoaded(List<FileLog> list) {
        if (list.size() > 0) {
            for (int i = 0; i < list.size(); i++) {
                try {
                    list.get(i).setEnvironment(session.getEnvironment());
                    fileLogRepository.updateFileLog(list.get(i));
                } catch (Exception e) {
                    LogFileUtils.logException(e);
                }
            }
            loadQueueFileLogs();
        }
    }

    public void getWorkflows() {

        for (String workflow : AppConstants.workflowsList) {
            String[] data = workflow.split("-");
            if (workflowRepository.getWorkFlowId(data[0], data[1], session.getEnvironment()) == null) {
                getWorkFlowsFromServer();
                break;
            }
        }
    }

    public void getWorkFlowsFromServer() {
        LogFileUtils.logInfo(TAG, "Workflow lists featching... ");
        retrofit.create(ApiService.class).getWorkflows(session.getAuthTokenWithBearer()).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<WorkflowsResponse>() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {

                    }

                    @Override
                    public void onNext(@NonNull WorkflowsResponse workflowsResponse) {
                        LogFileUtils.logInfo(TAG, "Workflow list successfully fetched... ");
                        ArrayList<String> appWorkflowList = new ArrayList<String>(Arrays.asList(AppConstants.workflowsList));

                        if (workflowsResponse != null && workflowsResponse.getWorkflows() != null && workflowsResponse.getWorkflows().size() > 0) {
                            String receivedWorkflow;
                            for (Workflow workflow : workflowsResponse.getWorkflows()) {
                                receivedWorkflow = workflow.getName() + "-" + workflow.getVersion();
                                if (appWorkflowList.contains(receivedWorkflow)) {
                                    workflow.setEnvironment(session.getEnvironment());
                                    workflowRepository.insertWorkflow(workflow);
                                }
                            }
                        }
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        LogFileUtils.logError(TAG, "error getting workflow lists... " + e.getMessage());
                    }

                    @Override
                    public void onComplete() {

                    }
                });
    }

    public void postWorkFlowsResult() {
        if (System.currentTimeMillis() - lastSyncResultTimeStamp < 15000) {
            return;
        }
        lastSyncResultTimeStamp = System.currentTimeMillis();
        postAutoDetectResult();
        postAppHeightResult();
        postAppPoseScoreResult();
        postChildDistance();
        postChildLightScore();
        postAppBoundingBoxResult();
        postAppOrientationResult();
    }

    public void postAutoDetectResult() {
        try {
            Gson gson = new GsonBuilder()
                    .excludeFieldsWithoutExposeAnnotation()
                    .create();
            List<FileLog> fileLogsList = fileLogRepository.loadAutoDetectedFileLog(session.getEnvironment());
            if (fileLogsList.size() == 0) {
                return;
            }
            LogFileUtils.logInfo(TAG, "Autodetect results posting... ");

            String workflow[] = AppConstants.APP_AUTO_DETECT_1_0.split("-");
            String appAutoDetectWorkflowId = workflowRepository.getWorkFlowId(workflow[0], workflow[1], session.getEnvironment());
            if (appAutoDetectWorkflowId == null) {
                return;
            }
            ArrayList<Results> resultList = new ArrayList();
            for (FileLog fileLog : fileLogsList) {
                ResultAutoDetect resultAutoDetect = new ResultAutoDetect();
                resultAutoDetect.setId(UUID.randomUUID().toString());
                resultAutoDetect.setGenerated(DataFormat.convertMilliSeconsToServerDate(fileLog.getCreateDate()));
                resultAutoDetect.setScan(fileLog.getScanServerId());
                resultAutoDetect.setWorkflow(appAutoDetectWorkflowId);
                ArrayList<String> sourceArtifacts = new ArrayList<>();
                sourceArtifacts.add(fileLog.getArtifactId());
                resultAutoDetect.setSource_artifacts(sourceArtifacts);
                ArrayList<String> sourceResults = new ArrayList<>();
                resultAutoDetect.setSource_results(sourceResults);
                ResultAutoDetect.Data data = new ResultAutoDetect.Data();
                data.setAuto_detected(fileLog.getChildDetected());
                resultAutoDetect.setData(data);
                resultList.add(resultAutoDetect);
            }
            ResultsData resultsData = new ResultsData();
            resultsData.setResults(resultList);
            RequestBody body = RequestBody.create(okhttp3.MediaType.parse("application/json; charset=utf-8"), (new JSONObject(gson.toJson(resultsData))).toString());

            onThreadChange(1);
            retrofit.create(ApiService.class).postWorkFlowsResult(session.getAuthTokenWithBearer(), body).subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Observer<ResultsData>() {
                        @Override
                        public void onSubscribe(@NonNull Disposable d) {

                        }

                        @Override
                        public void onNext(@NonNull ResultsData resultsData1) {
                            LogFileUtils.logInfo(TAG, "Autodetect workflow successfully posted...");
                            for (Results results : resultsData1.getResults()) {
                                FileLog fileLog = fileLogRepository.getFileLogByArtifactId(results.getSource_artifacts().get(0));
                                fileLog.setAutoDetectSynced(true);
                                fileLogRepository.updateFileLog(fileLog);
                            }
                            updated = true;
                            updateDelay = 0;
                            onThreadChange(-1);
                        }

                        @Override
                        public void onError(@NonNull Throwable e) {
                            LogFileUtils.logError(TAG, "Autodetect workflow posting failed " + e.getMessage());

                            if (NetworkUtils.isExpiredToken(e.getMessage())) {
                                AuthenticationHandler.restoreToken(context);
                                error401();
                            }
                            onThreadChange(-1);
                        }

                        @Override
                        public void onComplete() {

                        }
                    });
        } catch (Exception e) {
            LogFileUtils.logException(e);
        }
    }

    public void postAppHeightResult() {
        try {
            Gson gson = new GsonBuilder()
                    .excludeFieldsWithoutExposeAnnotation()
                    .create();
            List<FileLog> fileLogsList = fileLogRepository.loadAppHeightFileLog(session.getEnvironment());
            if (fileLogsList.size() == 0) {
                return;
            }
            String workflow[] = AppConstants.APP_HEIGHT_1_0.split("-");
            String appHeightWorkFlowId = workflowRepository.getWorkFlowId(workflow[0], workflow[1], session.getEnvironment());
            if (appHeightWorkFlowId == null) {
                return;
            }
            ArrayList<Results> resultList = new ArrayList();
            for (FileLog fileLog : fileLogsList) {
                ResultAppHeight resultAppHeight = new ResultAppHeight();
                resultAppHeight.setId(UUID.randomUUID().toString());
                resultAppHeight.setGenerated(DataFormat.convertMilliSeconsToServerDate(fileLog.getCreateDate()));
                resultAppHeight.setScan(fileLog.getScanServerId());
                resultAppHeight.setWorkflow(appHeightWorkFlowId);
                ArrayList<String> sourceArtifacts = new ArrayList<>();
                sourceArtifacts.add(fileLog.getArtifactId());
                resultAppHeight.setSource_artifacts(sourceArtifacts);
                ArrayList<String> sourceResults = new ArrayList<>();
                resultAppHeight.setSource_results(sourceResults);
                ResultAppHeight.Data data = new ResultAppHeight.Data();
                data.setHeight(fileLog.getChildHeight());
                resultAppHeight.setData(data);
                resultList.add(resultAppHeight);
            }
            ResultsData resultsData = new ResultsData();
            resultsData.setResults(resultList);

            RequestBody body = RequestBody.create(okhttp3.MediaType.parse("application/json; charset=utf-8"), (new JSONObject(gson.toJson(resultsData))).toString());

            onThreadChange(1);
            LogFileUtils.logInfo(TAG, "posting appHeight workflows... ");
            retrofit.create(ApiService.class).postWorkFlowsResult(session.getAuthTokenWithBearer(), body).subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Observer<ResultsData>() {
                        @Override
                        public void onSubscribe(@NonNull Disposable d) {

                        }

                        @Override
                        public void onNext(@NonNull ResultsData resultsData1) {
                            LogFileUtils.logInfo(TAG, "AppHeight workflow successfully posted...");
                            for (Results results : resultsData1.getResults()) {
                                FileLog fileLog = fileLogRepository.getFileLogByArtifactId(results.getSource_artifacts().get(0));
                                fileLog.setChildHeightSynced(true);
                                fileLogRepository.updateFileLog(fileLog);
                            }
                            updated = true;
                            updateDelay = 0;
                            onThreadChange(-1);
                        }

                        @Override
                        public void onError(@NonNull Throwable e) {
                            LogFileUtils.logError(TAG, "AppHeight workflow posting failed " + e.getMessage());

                            if (NetworkUtils.isExpiredToken(e.getMessage())) {
                                AuthenticationHandler.restoreToken(context);
                                error401();
                            }
                            onThreadChange(-1);
                        }

                        @Override
                        public void onComplete() {

                        }
                    });
        } catch (Exception e) {
            LogFileUtils.logException(e);
        }
    }


    public void postAppPoseScoreResult() {
        try {
            Gson gson = new GsonBuilder()
                    .excludeFieldsWithoutExposeAnnotation()
                    .create();
            List<FileLog> fileLogsList = fileLogRepository.loadAppPoseScoreFileLog(session.getEnvironment());
            if(fileLogsList!=null){
                LogFileUtils.logInfo(TAG,"Post app posecore size "+fileLogsList.size());
            }
            if (fileLogsList==null || fileLogsList.size() == 0) {
                return;
            }
            String workflow[] = AppConstants.APP_POSE_PREDICITION_1_0.split("-");
            String appPoseScoreWorkFlowId = workflowRepository.getWorkFlowId(workflow[0], workflow[1], session.getEnvironment());
            LogFileUtils.logInfo(TAG,"Post app posecore workflowid:wq "+fileLogsList.size());

            if (appPoseScoreWorkFlowId == null) {
                return;
            }
            ArrayList<Results> resultList = new ArrayList();
            for (FileLog fileLog : fileLogsList) {
                ResultAppScore resultAppScore = new ResultAppScore();
                resultAppScore.setId(UUID.randomUUID().toString());
                resultAppScore.setGenerated(DataFormat.convertMilliSeconsToServerDate(fileLog.getCreateDate()));
                resultAppScore.setScan(fileLog.getScanServerId());
                resultAppScore.setWorkflow(appPoseScoreWorkFlowId);
                ArrayList<String> sourceArtifacts = new ArrayList<>();
                sourceArtifacts.add(fileLog.getArtifactId());
                resultAppScore.setSource_artifacts(sourceArtifacts);
                ArrayList<String> sourceResults = new ArrayList<>();
                resultAppScore.setSource_results(sourceResults);
                ResultAppScore.Data data = new ResultAppScore.Data();
                data.setPoseScore(fileLog.getPoseScore());
                data.setPoseCoordinates(formatPoseCoordinates(fileLog.getPoseCoordinates(),fileLog.getPoseScore()));
                resultAppScore.setData(data);
                resultList.add(resultAppScore);
            }
            ResultsData resultsData = new ResultsData();
            resultsData.setResults(resultList);

            RequestBody body = RequestBody.create(okhttp3.MediaType.parse("application/json; charset=utf-8"), (new JSONObject(gson.toJson(resultsData))).toString());

            onThreadChange(1);
            LogFileUtils.logInfo(TAG, "posting appPoseScore workflows... ");
            retrofit.create(ApiService.class).postWorkFlowsResult(session.getAuthTokenWithBearer(), body).subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Observer<ResultsData>() {
                        @Override
                        public void onSubscribe(@NonNull Disposable d) {

                        }

                        @Override
                        public void onNext(@NonNull ResultsData resultsData1) {
                            LogFileUtils.logInfo(TAG, "AppPoseScore workflow successfully posted...");
                            for (Results results : resultsData1.getResults()) {
                                FileLog fileLog = fileLogRepository.getFileLogByArtifactId(results.getSource_artifacts().get(0));
                                fileLog.setPoseScoreSynced(true);
                                fileLogRepository.updateFileLog(fileLog);
                            }
                            updated = true;
                            updateDelay = 0;
                            onThreadChange(-1);
                        }

                        @Override
                        public void onError(@NonNull Throwable e) {
                            LogFileUtils.logError(TAG, "AppPoseScore workflow posting failed " + e.getMessage());

                            if (NetworkUtils.isExpiredToken(e.getMessage())) {
                                AuthenticationHandler.restoreToken(context);
                                error401();
                            }
                            onThreadChange(-1);
                        }

                        @Override
                        public void onComplete() {

                        }
                    });
        } catch (Exception e) {
            LogFileUtils.logException(e);
        }
    }

    public String formatPoseCoordinates(String poseCoordinates, float poseScore){
        String full = null;

        try {
        String[] result = poseCoordinates.split(" ");
        int i,j;
        String key_points_coordinate = "\"key_points_coordinate\":[";
        String key_points_prob = "\"key_points_prob\":[";
        for(i=0 ; i<result.length;i++){
            if(result[i] != null && !result[i].trim().isEmpty()) {
                String[] result1 = result[i].split(",");
                String landmarkType = Utils.getLandmarkType(i-1);
                if(result1!=null) {
                    key_points_coordinate = key_points_coordinate + "{" + "\""+landmarkType+"\":{\"x\":\"" + result1[1] + "\"," + "\"y\":\"" + result1[2] + "\"}},";
                    key_points_prob = key_points_prob + "{" + "\""+landmarkType+"\":{\"score\":\"" + result1[0] + "\"}},";
                }
            }


        }

            full = "{\"body_pose_score\":\"" + poseScore + "\"," + key_points_coordinate.substring(0, key_points_coordinate.length() - 1) + "],"
                    + key_points_prob.substring(0, key_points_prob.length() - 1) + "]}";
        }catch (Exception e){
            LogFileUtils.logException(e);

        }
        return full;

    }

    public void postChildLightScore()  {
        try {
            Gson gson = new GsonBuilder()
                    .excludeFieldsWithoutExposeAnnotation()
                    .create();
            List<FileLog> fileLogsList = fileLogRepository.loadChildLightScoreFileLog(session.getEnvironment());
            if(fileLogsList!=null){
                LogFileUtils.logInfo(TAG,"Post app child lightscore size "+fileLogsList.size());
            }
            if (fileLogsList==null || fileLogsList.size() == 0) {
                return;
            }
            String workflow[] = AppConstants.APP_LIGHT_SCORE_1_0.split("-");
            String appLightScoreWorkFlowId = workflowRepository.getWorkFlowId(workflow[0], workflow[1], session.getEnvironment());
            LogFileUtils.logInfo(TAG,"Post app light score workflowid:wq "+appLightScoreWorkFlowId);

            if (appLightScoreWorkFlowId == null) {
                return;
            }
            ArrayList<Results> resultList = new ArrayList();
            for (FileLog fileLog : fileLogsList) {
                ResultLightScore resultLightScore = new ResultLightScore();
                resultLightScore.setId(UUID.randomUUID().toString());
                resultLightScore.setGenerated(DataFormat.convertMilliSeconsToServerDate(fileLog.getCreateDate()));
                resultLightScore.setScan(fileLog.getScanServerId());
                resultLightScore.setWorkflow(appLightScoreWorkFlowId);
                ArrayList<String> sourceArtifacts = new ArrayList<>();
                sourceArtifacts.add(fileLog.getArtifactId());
                resultLightScore.setSource_artifacts(sourceArtifacts);
                ArrayList<String> sourceResults = new ArrayList<>();
                resultLightScore.setSource_results(sourceResults);
                ResultLightScore.Data data = new ResultLightScore.Data();
                data.setLight_score(String.valueOf(fileLog.getLight_score()));
                resultLightScore.setData(data);
                resultList.add(resultLightScore);
            }
            ResultsData resultsData = new ResultsData();
            resultsData.setResults(resultList);

            RequestBody body = RequestBody.create(okhttp3.MediaType.parse("application/json; charset=utf-8"), (new JSONObject(gson.toJson(resultsData))).toString());
            Log.i(TAG,"this is light score body "+(new JSONObject(gson.toJson(resultsData))).toString());
            onThreadChange(1);
            LogFileUtils.logInfo(TAG, "posting light score workflows... ");
            retrofit.create(ApiService.class).postWorkFlowsResult(session.getAuthTokenWithBearer(), body).subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Observer<ResultsData>() {
                        @Override
                        public void onSubscribe(@NonNull Disposable d) {

                        }

                        @Override
                        public void onNext(@NonNull ResultsData resultsData1) {
                            LogFileUtils.logInfo(TAG, "App light score workflow successfully posted...");
                            for (Results results : resultsData1.getResults()) {
                                FileLog fileLog = fileLogRepository.getFileLogByArtifactId(results.getSource_artifacts().get(0));
                                fileLog.setLight_score_synced(true);
                                fileLogRepository.updateFileLog(fileLog);
                            }
                            updated = true;
                            updateDelay = 0;
                            onThreadChange(-1);
                        }

                        @Override
                        public void onError(@NonNull Throwable e) {
                            LogFileUtils.logError(TAG, "App light score workflow posting failed " + e.getMessage());

                            if (NetworkUtils.isExpiredToken(e.getMessage())) {
                                AuthenticationHandler.restoreToken(context);
                                error401();
                            }
                            onThreadChange(-1);
                        }

                        @Override
                        public void onComplete() {

                        }
                    });
        } catch (Exception e) {
            LogFileUtils.logException(e);
        }
    }


    public void postChildDistance() {
        try {
            Gson gson = new GsonBuilder()
                    .excludeFieldsWithoutExposeAnnotation()
                    .create();
            List<FileLog> fileLogsList = fileLogRepository.loadChildDistanceFileLog(session.getEnvironment());
            if(fileLogsList!=null){
                LogFileUtils.logInfo(TAG,"Post app childdistance size "+fileLogsList.size());
            }
            if (fileLogsList==null || fileLogsList.size() == 0) {
                return;
            }
            String workflow[] = AppConstants.APP_CHILD_DISTANCE_1_0.split("-");
            String appChildDistanceWorkFlowId = workflowRepository.getWorkFlowId(workflow[0], workflow[1], session.getEnvironment());
            LogFileUtils.logInfo(TAG,"Post app childdistance workflowid:wq "+appChildDistanceWorkFlowId);

            if (appChildDistanceWorkFlowId == null) {
                return;
            }
            ArrayList<Results> resultList = new ArrayList();
            for (FileLog fileLog : fileLogsList) {
                ResultChildDistance resultChildDistanc = new ResultChildDistance();
                resultChildDistanc.setId(UUID.randomUUID().toString());
                resultChildDistanc.setGenerated(DataFormat.convertMilliSeconsToServerDate(fileLog.getCreateDate()));
                resultChildDistanc.setScan(fileLog.getScanServerId());
                resultChildDistanc.setWorkflow(appChildDistanceWorkFlowId);
                ArrayList<String> sourceArtifacts = new ArrayList<>();
                sourceArtifacts.add(fileLog.getArtifactId());
                resultChildDistanc.setSource_artifacts(sourceArtifacts);
                ArrayList<String> sourceResults = new ArrayList<>();
                resultChildDistanc.setSource_results(sourceResults);
                ResultChildDistance.Data data = new ResultChildDistance.Data();
                data.setChild_distance(String.valueOf(fileLog.getChild_distance()));
                resultChildDistanc.setData(data);
                resultList.add(resultChildDistanc);
            }
            ResultsData resultsData = new ResultsData();
            resultsData.setResults(resultList);

            RequestBody body = RequestBody.create(okhttp3.MediaType.parse("application/json; charset=utf-8"), (new JSONObject(gson.toJson(resultsData))).toString());

            onThreadChange(1);
            LogFileUtils.logInfo(TAG, "posting childdistance workflows... raw data "+(new JSONObject(gson.toJson(resultsData))).toString());
            retrofit.create(ApiService.class).postWorkFlowsResult(session.getAuthTokenWithBearer(), body).subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Observer<ResultsData>() {
                        @Override
                        public void onSubscribe(@NonNull Disposable d) {

                        }

                        @Override
                        public void onNext(@NonNull ResultsData resultsData1) {
                            LogFileUtils.logInfo(TAG, "App child distance workflow successfully posted...");
                            for (Results results : resultsData1.getResults()) {
                                FileLog fileLog = fileLogRepository.getFileLogByArtifactId(results.getSource_artifacts().get(0));
                                fileLog.setChild_distance_synced(true);
                                fileLogRepository.updateFileLog(fileLog);
                            }
                            updated = true;
                            updateDelay = 0;
                            onThreadChange(-1);
                        }

                        @Override
                        public void onError(@NonNull Throwable e) {
                            LogFileUtils.logError(TAG, "App childdistance workflow posting failed " + e.getMessage());

                            if (NetworkUtils.isExpiredToken(e.getMessage())) {
                                AuthenticationHandler.restoreToken(context);
                                error401();
                            }
                            onThreadChange(-1);
                        }

                        @Override
                        public void onComplete() {

                        }
                    });
        } catch (Exception e) {
            LogFileUtils.logException(e);
        }
    }

    public void postRemainingData() {

        lastSyncDailyReport = session.getLastSyncDailyReport();
        Log.i(TAG,"this is remaining data 1 "+lastSyncDailyReport);
        Log.i(TAG,"this is remaining data 1.5 "+(System.currentTimeMillis() - lastSyncDailyReport));
        if((System.currentTimeMillis() - lastSyncDailyReport) > 86400000 ) {
            session.setLastSyncDailyReport(System.currentTimeMillis());
            Log.i(TAG,"this is remaining data 2 "+session.getLastSyncDailyReport());

            try {
                Gson gson = new GsonBuilder()
                        .excludeFieldsWithoutExposeAnnotation()
                        .create();
                RemainingData remainingData = new RemainingData();
                remainingData.setArtifact((int) fileLogRepository.getArtifactCount());
                remainingData.setConsent(fileLogRepository.loadConsentFile(session.getEnvironment()).size());
                remainingData.setDevice_id(AppController.getInstance().getAndroidID());
                remainingData.setUser(session.getUserEmail());
                remainingData.setVersion(AppController.getInstance().getAppVersion());
                remainingData.setMeasure(measureRepository.getSyncableMeasure(session.getEnvironment()).size());
                remainingData.setPerson(personRepository.getSyncablePerson(session.getEnvironment()).size());
                remainingData.setScan(measureRepository.getSyncableMeasure(session.getEnvironment()).size());
                remainingData.setError("---");

                RequestBody body = RequestBody.create(okhttp3.MediaType.parse("application/json; charset=utf-8"), (new JSONObject(gson.toJson(remainingData))).toString());
                Log.i(TAG,"this is remaining data 3 "+(new JSONObject(gson.toJson(remainingData))).toString());

                onThreadChange(1);
                LogFileUtils.logInfo(TAG, "posting remaining data ");
                retrofit.create(ApiService.class).postRemainingData(session.getAuthTokenWithBearer(), body).subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(new Observer<RemainingData>() {
                            @Override
                            public void onSubscribe(@NonNull Disposable d) {

                            }


                            @Override
                            public void onNext(@NonNull RemainingData remainingData1) {
                                LogFileUtils.logInfo(TAG, "RemainingData successfully posted");
                                Log.i(TAG,"this is remaining data 4 update ");

                                updated = true;
                                updateDelay = 0;
                                onThreadChange(-1);
                            }

                            @Override
                            public void onError(@NonNull Throwable e) {
                                LogFileUtils.logError(TAG, "RemainingData posting failed " + e.getMessage());
                                Log.i(TAG,"this is remaining data 5 error "+e.getMessage());

                                try {
                                    LogFileUtils.logError(TAG, "RemainingData request body " + new JSONObject(gson.toJson(remainingData)));
                                } catch (JSONException jsonException) {
                                    jsonException.printStackTrace();
                                }
                                if (NetworkUtils.isExpiredToken(e.getMessage())) {
                                    AuthenticationHandler.restoreToken(context);
                                    error401();
                                }
                                onThreadChange(-1);
                            }

                            @Override
                            public void onComplete() {

                            }
                        });
            } catch (Exception e) {
                LogFileUtils.logException(e);
            }
        }
    }


    public void error401(){
        int count = session.getSessionError()+1;
        session.setSessionError(count);
        LogFileUtils.logInfo(TAG,"error 401 "+count);
    }

    public void postAppBoundingBoxResult() {
        try {
            Gson gson = new GsonBuilder()
                    .excludeFieldsWithoutExposeAnnotation()
                    .create();
            List<FileLog> fileLogsList = fileLogRepository.loadAppBoundingBox(session.getEnvironment());
            if(fileLogsList!=null){
                LogFileUtils.logInfo(TAG,"Post app boundingbox size "+fileLogsList.size());
            }
            if (fileLogsList==null || fileLogsList.size() == 0) {
                return;
            }
            String workflow[] = AppConstants.APP_BOUNDING_BOX_1_0.split("-");
            String appBoundingBoxWorkFlowId = workflowRepository.getWorkFlowId(workflow[0], workflow[1], session.getEnvironment());
            LogFileUtils.logInfo(TAG,"Post app bounding box workflowid:wq "+fileLogsList.size());

            if (appBoundingBoxWorkFlowId == null) {
                return;
            }
            ArrayList<Results> resultList = new ArrayList();
            for (FileLog fileLog : fileLogsList) {
                ResultBoundingBox resultBoundingBox = new ResultBoundingBox();
                resultBoundingBox.setId(UUID.randomUUID().toString());
                resultBoundingBox.setGenerated(DataFormat.convertMilliSeconsToServerDate(fileLog.getCreateDate()));
                resultBoundingBox.setScan(fileLog.getScanServerId());
                resultBoundingBox.setWorkflow(appBoundingBoxWorkFlowId);
                ArrayList<String> sourceArtifacts = new ArrayList<>();
                sourceArtifacts.add(fileLog.getArtifactId());
                resultBoundingBox.setSource_artifacts(sourceArtifacts);
                ArrayList<String> sourceResults = new ArrayList<>();
                resultBoundingBox.setSource_results(sourceResults);
                ResultBoundingBox.Data data = new ResultBoundingBox.Data();
                data.setAppBoundingBox(fileLog.getBoundingBox());
                resultBoundingBox.setData(data);
                resultList.add(resultBoundingBox);
            }
            ResultsData resultsData = new ResultsData();
            resultsData.setResults(resultList);

            RequestBody body = RequestBody.create(okhttp3.MediaType.parse("application/json; charset=utf-8"), (new JSONObject(gson.toJson(resultsData))).toString());

            onThreadChange(1);
            LogFileUtils.logInfo(TAG, "posting appPoseScore workflows... ");
            retrofit.create(ApiService.class).postWorkFlowsResult(session.getAuthTokenWithBearer(), body).subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Observer<ResultsData>() {
                        @Override
                        public void onSubscribe(@NonNull Disposable d) {

                        }

                        @Override
                        public void onNext(@NonNull ResultsData resultsData1) {
                            LogFileUtils.logInfo(TAG, "AppPoseScore workflow successfully posted...");
                            for (Results results : resultsData1.getResults()) {
                                FileLog fileLog = fileLogRepository.getFileLogByArtifactId(results.getSource_artifacts().get(0));
                                fileLog.setBounding_box_synced(true);
                                fileLogRepository.updateFileLog(fileLog);
                            }
                            updated = true;
                            updateDelay = 0;
                            onThreadChange(-1);
                        }

                        @Override
                        public void onError(@NonNull Throwable e) {
                            LogFileUtils.logError(TAG, "AppPoseScore workflow posting failed " + e.getMessage());

                            if (NetworkUtils.isExpiredToken(e.getMessage())) {
                                AuthenticationHandler.restoreToken(context);
                                error401();
                            }
                            onThreadChange(-1);
                        }

                        @Override
                        public void onComplete() {

                        }
                    });
        } catch (Exception e) {
            LogFileUtils.logException(e);
        }
    }

    public void postAppOrientationResult() {
        try {
            Gson gson = new GsonBuilder()
                    .excludeFieldsWithoutExposeAnnotation()
                    .create();
            List<FileLog> fileLogsList = fileLogRepository.loadAppOrientation(session.getEnvironment());
            if(fileLogsList!=null){
                LogFileUtils.logInfo(TAG,"Post app orientation size "+fileLogsList.size());
            }
            if (fileLogsList==null || fileLogsList.size() == 0) {
                return;
            }
            String workflow[] = AppConstants.APP_ORIENTATION_1_0.split("-");
            String appOrientationWorkFlowId = workflowRepository.getWorkFlowId(workflow[0], workflow[1], session.getEnvironment());
            LogFileUtils.logInfo(TAG,"Post app orientation box workflowid:wq "+fileLogsList.size());

            if (appOrientationWorkFlowId == null) {
                return;
            }
            ArrayList<Results> resultList = new ArrayList();
            for (FileLog fileLog : fileLogsList) {
                ResultOrientation resultOrientation = new ResultOrientation();
                resultOrientation.setId(UUID.randomUUID().toString());
                resultOrientation.setGenerated(DataFormat.convertMilliSeconsToServerDate(fileLog.getCreateDate()));
                resultOrientation.setScan(fileLog.getScanServerId());
                resultOrientation.setWorkflow(appOrientationWorkFlowId);
                ArrayList<String> sourceArtifacts = new ArrayList<>();
                sourceArtifacts.add(fileLog.getArtifactId());
                resultOrientation.setSource_artifacts(sourceArtifacts);
                ArrayList<String> sourceResults = new ArrayList<>();
                resultOrientation.setSource_results(sourceResults);
                ResultOrientation.Data data = new ResultOrientation.Data();
                data.setAppOrientation(fileLog.getOrientation());
                resultOrientation.setData(data);
                resultList.add(resultOrientation);
            }
            ResultsData resultsData = new ResultsData();
            resultsData.setResults(resultList);

            RequestBody body = RequestBody.create(okhttp3.MediaType.parse("application/json; charset=utf-8"), (new JSONObject(gson.toJson(resultsData))).toString());

            onThreadChange(1);
            LogFileUtils.logInfo(TAG, "posting appOrientation workflows... ");
            retrofit.create(ApiService.class).postWorkFlowsResult(session.getAuthTokenWithBearer(), body).subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Observer<ResultsData>() {
                        @Override
                        public void onSubscribe(@NonNull Disposable d) {

                        }

                        @Override
                        public void onNext(@NonNull ResultsData resultsData1) {
                            LogFileUtils.logInfo(TAG, "AppOrientation workflow successfully posted...");
                            for (Results results : resultsData1.getResults()) {
                                FileLog fileLog = fileLogRepository.getFileLogByArtifactId(results.getSource_artifacts().get(0));
                                fileLog.setOrientation_synced(true);
                                fileLogRepository.updateFileLog(fileLog);
                            }
                            updated = true;
                            updateDelay = 0;
                            onThreadChange(-1);
                        }

                        @Override
                        public void onError(@NonNull Throwable e) {
                            LogFileUtils.logError(TAG, "AppPoseScore workflow posting failed " + e.getMessage());

                            if (NetworkUtils.isExpiredToken(e.getMessage())) {
                                AuthenticationHandler.restoreToken(context);
                                error401();
                            }
                            onThreadChange(-1);
                        }

                        @Override
                        public void onComplete() {

                        }
                    });
        } catch (Exception e) {
            LogFileUtils.logException(e);
        }
    }
}
